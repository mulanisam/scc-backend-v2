package com.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.app.dto.CustomerPaymentDTO;
import com.app.dto.TradingEntryDto;
import com.app.dto.TradingPaymentDto;
import com.app.dto.payment.PaymentView;
import com.app.entity.Customer;
import com.app.entity.CustomerLedger;
import com.app.entity.MessageOutbox.Channel;
import com.app.entity.MessageOutbox.MessageType;
import com.app.entity.Party;
import com.app.entity.Supplier;
import com.app.entity.TradingEntry;
import com.app.repository.PartyRepository;
import com.app.repository.SupplierRepository;
import com.app.repository.TradingEntryRepository;
import com.app.config.MessagingProperties;
import com.app.exception.ResourceNotFoundException;
import com.app.utility.MoneyRules;
import com.app.utility.SmsMessageBuilder;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TradingServiceImpl implements TradingService {

    private static final Logger logger = LoggerFactory.getLogger(TradingServiceImpl.class);

    private final TradingEntryRepository tradingEntryRepository;
    private final PartyRepository partyRepository;
    private final SupplierRepository supplierRepository;
    private final LedgerService ledgerService;
    private final MessagingService messagingService;
    private final MessagingProperties messagingProperties;
    /** A party receipt is a customer receipt; this is what keeps it that way. */
    private final PaymentService paymentService;

    @Override
    @Transactional
    public TradingEntry createTradingEntry(TradingEntryDto dto) {
        logger.debug("Creating trading entry for party {} on {}", dto.getPartyId(), dto.getDate());

        if (dto.getDate() == null) {
            throw new IllegalArgumentException("A trading entry must have a date.");
        }
        // The same rule the sales screens enforce: an entry cannot be recorded before it
        // happens, and the API is reachable without the browser.
        if (dto.getDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Trading date " + dto.getDate()
                    + " is in the future. Future-dated entries cannot be saved.");
        }

        try {
            /*
             * Calculated here, not trusted from the request, and a disagreement is refused
             * rather than silently overwritten - the same rule as bulk and single sales.
             * Trading took the submitted amount, pending and balance as given, so a browser
             * rounding differently from the server would have written a figure the ledger
             * disagreed with.
             */
            BigDecimal amount = MoneyRules.calculateAmount(dto.getKilograms(), dto.getRate());
            if (dto.getAmount() != null && !MoneyRules.amountsMatch(amount, dto.getAmount())) {
                throw new IllegalArgumentException(String.format(
                        "Amount mismatch: %s kg at rate %s is %s, but %s was submitted.",
                        dto.getKilograms(), dto.getRate(),
                        amount.toPlainString(), dto.getAmount().toPlainString()));
            }
            BigDecimal payment = MoneyRules.money(dto.getPayment());
            BigDecimal pending = MoneyRules.calculatePending(amount, payment);

            TradingEntry entry = new TradingEntry();
            entry.setDate(dto.getDate());
            entry.setBirds(dto.getBirds());
            entry.setBirdsSold(dto.getBirds());
            entry.setKilograms(dto.getKilograms());
            entry.setRate(dto.getRate());
            entry.setAmount(amount);
            entry.setPayment(payment);
            entry.setPending(pending);
            // Upper-cased for the same reason a party payment is: the trading screen sends
            // "cash" and every other screen sends "CASH", and the reports group on it - so
            // the two spellings appear as two payment methods, each with half the money.
            // Existing rows keep whatever they were written with; this only fixes new ones.
            entry.setPaymentMode(normalisePaymentMode(dto.getPaymentMode()));
            entry.setDescription(dto.getDescription());
            entry.setObsolete(false);

            Party party = partyRepository.findById(dto.getPartyId())
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Party " + dto.getPartyId() + " was not found."));
            entry.setParty(party);

            /*
             * The ledger account. Required, because an entry that cannot post is an
             * invoice nobody can collect: the balance, the statement and the daily message
             * all read customer_ledger, and route 9's 44 lakh is there.
             */
            if (party.getCustomer() == null) {
                throw new IllegalStateException(party.getName()
                        + " has no ledger account, so nothing can be billed to them."
                        + " Link the party to a customer first.");
            }
            entry.setCustomer(party.getCustomer());

            // Optional now: a sale to a party need not name where the birds came from,
            // and the 571 converted entries have no supplier recorded at all.
            if (dto.getSupplierId() != null) {
                entry.setSupplier(supplierRepository.findById(dto.getSupplierId())
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Supplier " + dto.getSupplierId() + " was not found.")));
            }

            /*
             * The vehicle is recorded as its number, and it has to be one of this
             * party's.
             *
             * Checked rather than taken on trust, because it is no longer a foreign key:
             * the entry keeps saying which vehicle came even after that vehicle leaves
             * the party's list, which is what a record of a past load should do, but it
             * means nothing stops a typo at the point of entry except this.
             */
            String vehicle = dto.getVehicleNumber() == null
                    ? "" : dto.getVehicleNumber().replaceAll("[\\s-]+", "").toUpperCase();
            if (vehicle.isEmpty()) {
                throw new IllegalArgumentException("A trading entry must say which vehicle carried the load.");
            }
            if (!party.getVehicleNumbers().contains(vehicle)) {
                throw new IllegalArgumentException(vehicle + " is not one of "
                        + party.getName() + "'s vehicles. Add it to the party first.");
            }
            entry.setVehicleNumber(vehicle);

            TradingEntry savedEntry = tradingEntryRepository.save(entry);

            // The ledger is what makes the balance move. Without this a trading entry was
            // recorded and the party still owed exactly what they owed before.
            CustomerLedger ledger = ledgerService.createTradingLedgerEntry(savedEntry);
            savedEntry.setBalanceAmount(ledger.getRunningBalance());
            tradingEntryRepository.save(savedEntry);

            queueTradingMessages(savedEntry, dto.isSendSms(), dto.isSendWhatsapp());

            logger.info("Trading entry saved successfully with id: {}", savedEntry.getId());
            return savedEntry;

        } catch (IllegalArgumentException | IllegalStateException | ResourceNotFoundException e) {
            // Business rejections keep their type so the handler reports them as 400 or
            // 404 with the message intact, rather than as "Failed to create trading
            // entry" with the reason buried in the log.
            throw e;
        } catch (Exception e) {
            logger.error("Error creating trading entry", e);
            throw new RuntimeException("Failed to create trading entry: " + e.getMessage());
        }
    }

    /**
     * Tells the party what was delivered, on whichever channels were asked for.
     *
     * The same message a retail customer gets for the day's trading, built by the same
     * builder and queued through the same outbox - so a wholesale party appears in the
     * messaging dashboard beside everyone else, with the same delivery status and the same
     * resend button, rather than in a second system nobody watches.
     *
     * The entry id is the occurrence, because a party can take two loads in a day and each
     * is its own delivery; a per-day key would have dropped the second.
     */
    private void queueTradingMessages(TradingEntry entry, boolean sendSms, boolean sendWhatsapp) {
        if (!sendSms && !sendWhatsapp) {
            return;
        }

        try {
            Customer customer = entry.getCustomer();
            LocalDate date = entry.getDate();
            BigDecimal balance = MoneyRules.money(entry.getBalanceAmount());

            if (sendSms) {
                SmsMessageBuilder.Message sms =
                        SmsMessageBuilder.dailyBalance(customer.getName(), date, balance);
                messagingService.enqueue(customer, Channel.SMS, MessageType.DAILY_SALE_SUMMARY,
                        date, null, sms.variables(), sms.body(), entry.getId());
            }

            if (sendWhatsapp) {
                boolean withRate = messagingProperties.getFast2sms().isWhatsappDailyIncludesRate();
                Integer birds = entry.getBirdsSold() == null ? entry.getBirds() : entry.getBirdsSold();
                long birdCount = birds == null ? 0L : birds;

                SmsMessageBuilder.Message whatsapp = withRate
                        ? SmsMessageBuilder.dailySaleSummary(customer.getName(), date, birdCount,
                                entry.getKilograms(), entry.getAmount(), entry.getPayment(), balance)
                        : SmsMessageBuilder.dailySaleSummaryNoRate(customer.getName(), date, birdCount,
                                entry.getKilograms(), entry.getAmount(), entry.getPayment(), balance);

                messagingService.enqueue(customer, Channel.WHATSAPP, MessageType.DAILY_SALE_SUMMARY,
                        date, null, whatsapp.variables(), whatsapp.body(), entry.getId());
            }
        } catch (RuntimeException e) {
            // A message that cannot be queued must never fail the entry. The outbox is
            // the record, so a gap in it is visible afterwards.
            logger.error("Could not queue messages for trading entry {}: {}",
                    entry.getId(), e.getMessage());
        }
    }

    /**
     * Records money received from a party.
     *
     * Delegates to the retail payment service rather than writing its own row, and that is
     * the whole design: a party's account *is* a customer ledger account - route 9's
     * ₹44,03,490 sits in customer_ledger - so a receipt entered here has to land in the same
     * table, move the same balance, appear on the same statement and be cancellable by the
     * same code. A second payment path would have given a party two answers to what they
     * have paid, and the statement would have shown whichever one it happened to read.
     *
     * What this replaces is worse than a second path: nothing. The screen posted to
     * /trading/payment, which no controller has ever mapped, so every party payment ever
     * entered here returned 404 and was reported as "Error creating payment entry". The
     * money was taken and the balance never moved.
     */
    @Override
    @Transactional
    public PaymentView createPartyPayment(TradingPaymentDto dto) {
        if (dto.getPartyId() == null) {
            throw new IllegalArgumentException("A payment must say which party it came from.");
        }
        if (dto.getDate() == null) {
            throw new IllegalArgumentException("A payment must have a date.");
        }
        // Same rule as an entry: the API is reachable without the browser, and a receipt
        // dated next week would sit above every later row in the statement.
        if (dto.getDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Payment date " + dto.getDate()
                    + " is in the future. Future-dated payments cannot be saved.");
        }

        Party party = partyRepository.findById(dto.getPartyId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Party " + dto.getPartyId() + " was not found."));

        if (party.getCustomer() == null) {
            throw new IllegalStateException(party.getName()
                    + " has no ledger account, so a payment cannot be credited to them."
                    + " Link the party to a customer first.");
        }

        CustomerPaymentDTO receipt = new CustomerPaymentDTO();
        receipt.setCustomerId(party.getCustomer().getId());
        receipt.setPaymentDate(dto.getDate());
        receipt.setAmount(dto.getPayment());
        receipt.setPaymentMode(normalisePaymentMode(dto.getPaymentMode()));
        receipt.setTransactionReference(dto.getTransactionId());
        receipt.setRemarks(dto.getDescription());
        receipt.setSendSms(dto.isSendSms());
        receipt.setSendWhatsapp(dto.isSendWhatsapp());

        logger.info("Recording a payment of {} from party {} ({})",
                dto.getPayment(), party.getId(), party.getName());

        // Mapped here, inside the transaction, because the customer behind a party is a lazy
        // proxy: reading its name after the session closes would throw, and handing the
        // entity to Jackson fails outright on the proxy's interceptor.
        return PaymentView.of(paymentService.createPayment(receipt));
    }

    /**
     * The trading screen sends "cash"; the retail one sends "CASH".
     *
     * Upper-cased here rather than left as typed, because payment_mode is read back as a
     * label and grouped on in the reports - "cash" and "CASH" would show as two payment
     * methods on the same report, each with half the money.
     */
    private static String normalisePaymentMode(String mode) {
        return mode == null || mode.isBlank() ? "CASH" : mode.trim().toUpperCase();
    }

    @Override
    public Integer getBalanceAmount(Long partyId, Long vendorId) {
        logger.info("Fetching balance amount for partyId: {}, vendorId: {}", partyId, vendorId);
        try {
            // Placeholder: Implement your balance amount fetching logic here
            return 0;
        } catch (Exception e) {
            logger.error("Error fetching balance amount", e);
            throw new RuntimeException("Error fetching balance amount");
        }
    }
}
