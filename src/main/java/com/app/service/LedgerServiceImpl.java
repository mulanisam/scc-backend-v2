package com.app.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.CustomerLedgerDTO;
import com.app.dto.CustomerStatementDTO;
import com.app.dto.LedgerStatementTotals;
import com.app.entity.Customer;
import com.app.entity.CustomerLedger;
import com.app.entity.CustomerLedger.TransactionType;
import com.app.entity.CustomerPayment;
import com.app.entity.Sale;
import com.app.entity.TradingEntry;
import com.app.entity.TradingTrip;
import com.app.exception.ResourceNotFoundException;
import com.app.repository.CustomerLedgerRepository;
import com.app.repository.CustomerRepository;
import com.app.repository.SaleRepository;
import com.app.repository.TradingEntryRepository;
import com.app.utility.MoneyRules;

@Service
public class LedgerServiceImpl implements LedgerService {

    private static final Logger logger = LoggerFactory.getLogger(LedgerServiceImpl.class);

    @Autowired
    private CustomerLedgerRepository ledgerRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private SaleRepository saleRepository;

    /**
     * Read only to fill in quantities on a statement.
     *
     * Route 9's 571 ledger rows point at trading entries rather than sales, and without
     * this their statements showed every line's birds, weight and rate as zero.
     */
    @Autowired
    private TradingEntryRepository tradingEntryRepository;

    @Override
    @Transactional
    public CustomerLedger createSaleLedgerEntry(Sale sale) {
        logger.info("Creating ledger entry for sale ID: {}", sale.getId());

        // Loaded rather than taken from the sale.
        //
        // On the bulk path SaleMapper builds each Sale with a stub Customer that
        // carries nothing but an id, which is enough for the foreign key but not to
        // be saved: customerRepository.save(stub) is a merge, and merging a stub
        // copies its nulls over the real row - "Column 'city_id' cannot be null".
        // The entityManager.clear() that used to sit at the end of salesBulkEntry
        // was discarding that bad merge before it could flush, which is why the
        // damage never appeared. With the clear() gone, the fix is to work with the
        // managed entity.
        Customer customer = customerRepository.findById(sale.getCustomer().getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Customer " + sale.getCustomer().getId() + " was not found."));

        CustomerLedger ledger = new CustomerLedger();
        ledger.setCustomer(customer);
        ledger.setTransactionDate(sale.getDate());
        ledger.setTransactionType(TransactionType.SALE);
        ledger.setReferenceType("SALE");
        ledger.setReferenceId(sale.getId());
        
        // Debit = Sale amount (increases customer's debt)
        BigDecimal saleAmount = MoneyRules.money(sale.getAmount());
        ledger.setDebitAmount(saleAmount);
        
        // Credit = Payment received (decreases debt)
        BigDecimal paymentAmount = MoneyRules.money(sale.getPayment());
        ledger.setCreditAmount(paymentAmount);
        
        ledger.setPaymentMode(sale.getPaymentMode());
        ledger.setDescription("Sale - " + (sale.getBirds() != null ? sale.getBirds() + " birds, " : "") + 
                             (sale.getKilograms() != null ? sale.getKilograms() + " kg" : ""));
        
        // Check if this is a backdated entry
        LocalDate today = LocalDate.now();
        if (sale.getDate().isBefore(today)) {
            ledger.setBackdated(true);
        }
        
        // Calculate running balance
        BigDecimal previousBalance = getCurrentBalance(customer);
        BigDecimal runningBalance = MoneyRules.money(previousBalance.add(saleAmount).subtract(paymentAmount));
        ledger.setRunningBalance(runningBalance);
        
        CustomerLedger savedLedger = ledgerRepository.save(ledger);
        
        // If backdated, recalculate all balances from this date
        if (ledger.isBackdated()) {
            logger.info("Backdated sale detected. Recalculating balances from date: {}", sale.getDate());
            recalculateBalancesFromDate(customer, sale.getDate());
        } else {
            // Update customer balance
            customer.setBalanceAmount(runningBalance);
            customerRepository.save(customer);
        }
        
        return savedLedger;
    }

    @Override
    @Transactional
    public CustomerLedger createTradingLedgerEntry(TradingEntry entry) {
        logger.info("Creating ledger entry for trading entry ID: {}", entry.getId());

        if (entry.getCustomer() == null) {
            throw new IllegalStateException("Trading entry " + entry.getId()
                    + " has no ledger account. Link the party to a customer before billing it.");
        }

        // Loaded for the same reason the sale path loads it: a detached or stub Customer
        // merged back would copy its nulls over the real row.
        Customer customer = customerRepository.findById(entry.getCustomer().getId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Customer " + entry.getCustomer().getId() + " was not found."));

        CustomerLedger ledger = new CustomerLedger();
        ledger.setCustomer(customer);
        ledger.setTransactionDate(entry.getDate());
        // SALE, not a type of its own: a statement should read one account's history
        // without the reader having to know which subsystem recorded each line. The
        // reference type is what distinguishes them.
        ledger.setTransactionType(TransactionType.SALE);
        ledger.setReferenceType(REFERENCE_TRADING_ENTRY);
        ledger.setReferenceId(entry.getId());

        BigDecimal amount = MoneyRules.money(entry.getAmount());
        BigDecimal payment = MoneyRules.money(entry.getPayment());
        ledger.setDebitAmount(amount);
        ledger.setCreditAmount(payment);
        ledger.setPaymentMode(entry.getPaymentMode());

        Integer birds = entry.getBirdsSold() == null ? entry.getBirds() : entry.getBirdsSold();
        ledger.setDescription("Trading - "
                + (birds != null ? birds + " birds, " : "")
                + (entry.getKilograms() != null ? entry.getKilograms() + " kg" : ""));

        if (entry.getDate() != null && entry.getDate().isBefore(LocalDate.now())) {
            ledger.setBackdated(true);
        }

        BigDecimal runningBalance = MoneyRules.money(
                getCurrentBalance(customer).add(amount).subtract(payment));
        ledger.setRunningBalance(runningBalance);

        CustomerLedger saved = ledgerRepository.save(ledger);

        if (ledger.isBackdated()) {
            logger.info("Back-dated trading entry. Recalculating balances from {}", entry.getDate());
            recalculateBalancesFromDate(customer, entry.getDate());
        } else {
            customer.setBalanceAmount(runningBalance);
            customerRepository.save(customer);
        }

        return saved;
    }

    @Override
    @Transactional
    public CustomerLedger createPaymentLedgerEntry(CustomerPayment payment) {
        logger.info("Creating ledger entry for payment ID: {}", payment.getId());
        
        Customer customer = payment.getCustomer();
        CustomerLedger ledger = new CustomerLedger();
        ledger.setCustomer(customer);
        ledger.setTransactionDate(payment.getPaymentDate());
        ledger.setTransactionType(TransactionType.PAYMENT);
        ledger.setReferenceType("PAYMENT");
        ledger.setReferenceId(payment.getId());
        ledger.setDebitAmount(MoneyRules.money(BigDecimal.ZERO));
        ledger.setCreditAmount(MoneyRules.money(payment.getAmount()));
        ledger.setPaymentMode(payment.getPaymentMode());
        ledger.setDescription("Payment received - " + payment.getPaymentMode() + 
                             (payment.getTransactionReference() != null ? " (" + payment.getTransactionReference() + ")" : ""));
        
        // Check if backdated
        LocalDate today = LocalDate.now();
        if (payment.getPaymentDate().isBefore(today)) {
            ledger.setBackdated(true);
        }
        
        // Calculate running balance
        BigDecimal previousBalance = getCurrentBalance(customer);
        BigDecimal runningBalance = MoneyRules.money(previousBalance.subtract(MoneyRules.money(payment.getAmount())));
        ledger.setRunningBalance(runningBalance);
        
        CustomerLedger savedLedger = ledgerRepository.save(ledger);
        
        // If backdated, recalculate all balances from this date
        if (ledger.isBackdated()) {
            logger.info("Backdated payment detected. Recalculating balances from date: {}", payment.getPaymentDate());
            recalculateBalancesFromDate(customer, payment.getPaymentDate());
        } else {
            // Update customer balance
            customer.setBalanceAmount(runningBalance);
            customerRepository.save(customer);
        }
        
        return savedLedger;
    }

    @Override
    @Transactional
    public CustomerLedger createOpeningBalanceEntry(Customer customer, BigDecimal openingBalance, LocalDate asOfDate) {
        logger.info("Creating opening balance entry for customer: {}, balance: {}", customer.getId(), openingBalance);
        
        CustomerLedger ledger = new CustomerLedger();
        ledger.setCustomer(customer);
        ledger.setTransactionDate(asOfDate);
        ledger.setTransactionType(TransactionType.OPENING_BALANCE);
        ledger.setReferenceType("OPENING_BALANCE");
        ledger.setReferenceId(null);
        ledger.setDebitAmount(openingBalance.signum() > 0 ? MoneyRules.money(openingBalance) : MoneyRules.money(BigDecimal.ZERO));
        ledger.setCreditAmount(openingBalance.signum() < 0 ? MoneyRules.money(openingBalance.abs()) : MoneyRules.money(BigDecimal.ZERO));
        ledger.setRunningBalance(MoneyRules.money(openingBalance));
        ledger.setDescription("Opening Balance");
        ledger.setBackdated(false);
        
        return ledgerRepository.save(ledger);
    }

    @Override
    public List<CustomerLedgerDTO> getCustomerLedger(Long customerId, LocalDate startDate, LocalDate endDate) {
        logger.info("Fetching ledger for customer: {}, from: {}, to: {}", customerId, startDate, endDate);
        
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        
        List<CustomerLedger> ledgerEntries;
        
        if (startDate != null && endDate != null) {
            ledgerEntries = ledgerRepository.findByCustomerAndTransactionDateBetweenOrderByTransactionDateAsc(
                    customer, startDate, endDate);
        } else {
            ledgerEntries = ledgerRepository.findByCustomerOrderByTransactionDateAsc(customer);
        }
        
        return ledgerEntries.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public BigDecimal getCurrentBalance(Customer customer) {
        List<CustomerLedger> latestEntry = ledgerRepository.findLatestByCustomer(customer);
        if (latestEntry.isEmpty()) {
            return MoneyRules.money(BigDecimal.ZERO);
        }
        return latestEntry.get(0).getRunningBalance();
    }

    @Override
    @Transactional
    public void recalculateBalancesFromDate(Customer customer, LocalDate fromDate) {
        logger.info("Recalculating balances for customer: {} from date: {}", customer.getId(), fromDate);
        
        // The balance carried into fromDate. Read as a single indexed row: this
        // previously loaded the customer's entire history back to 1900 and threw
        // all but the last row away, and it runs once per sale line - a trip of
        // 116 lines for yesterday's date loaded a customer's whole ledger 116
        // times over. Customers here average 116 ledger rows and reach 499.
        BigDecimal startingBalance = balanceBefore(customer, fromDate);

        // Get all entries from fromDate onwards
        List<CustomerLedger> entriesToRecalculate = ledgerRepository
                .findByCustomerAndTransactionDateGreaterThanEqualOrderByTransactionDateAsc(customer, fromDate);
        
        // Sort by date and then by ID to ensure consistent ordering
        entriesToRecalculate.sort(Comparator
                .comparing(CustomerLedger::getTransactionDate)
                .thenComparing(CustomerLedger::getId));
        
        BigDecimal runningBalance = startingBalance;
        
        // Recalculate running balance for each entry
        for (CustomerLedger entry : entriesToRecalculate) {
            runningBalance = MoneyRules.money(runningBalance.add(MoneyRules.money(entry.getDebitAmount())).subtract(MoneyRules.money(entry.getCreditAmount())));
            entry.setRunningBalance(runningBalance);
            entry.setUpdatedAt(LocalDateTime.now());
        }
        
        // Save all updated entries
        ledgerRepository.saveAll(entriesToRecalculate);
        
        // Update customer's current balance
        customer.setBalanceAmount(runningBalance);
        customerRepository.save(customer);
        
        logger.info("Balance recalculation complete. New balance: {}", runningBalance);
    }

    @Override
    @Transactional
    public void recalculateAllBalances(Customer customer) {
        logger.info("Recalculating ALL balances for customer: {}", customer.getId());
        
        List<CustomerLedger> allEntries = ledgerRepository.findByCustomerOrderByTransactionDateAsc(customer);
        
        // Sort by date and then by ID
        allEntries.sort(Comparator
                .comparing(CustomerLedger::getTransactionDate)
                .thenComparing(CustomerLedger::getId));
        
        BigDecimal runningBalance = MoneyRules.money(BigDecimal.ZERO);
        
        for (CustomerLedger entry : allEntries) {
            runningBalance = MoneyRules.money(runningBalance.add(MoneyRules.money(entry.getDebitAmount())).subtract(MoneyRules.money(entry.getCreditAmount())));
            entry.setRunningBalance(runningBalance);
            entry.setUpdatedAt(LocalDateTime.now());
        }
        
        ledgerRepository.saveAll(allEntries);
        
        // Update customer balance
        customer.setBalanceAmount(runningBalance);
        customerRepository.save(customer);
        
        logger.info("Complete balance recalculation done. Final balance: {}", runningBalance);
    }

    @Override
    public boolean isCreditLimitExceeded(Customer customer, BigDecimal additionalAmount) {
        if (!customer.isCreditLimitEnabled() || customer.getCreditLimit() == null) {
            return false; // No credit limit
        }
        
        BigDecimal currentBalance = getCurrentBalance(customer);
        BigDecimal newBalance = MoneyRules.money(currentBalance.add(MoneyRules.money(additionalAmount)));
        
        return newBalance.compareTo(customer.getCreditLimit()) > 0;
    }

    @Override
    @Transactional
    public void updateCustomerBalance(Customer customer) {
        BigDecimal currentBalance = getCurrentBalance(customer);
        customer.setBalanceAmount(currentBalance);
        customerRepository.save(customer);
    }

    private CustomerLedgerDTO convertToDTO(CustomerLedger ledger) {
        CustomerLedgerDTO dto = new CustomerLedgerDTO();
        dto.setId(ledger.getId());
        dto.setCustomerId(ledger.getCustomer().getId());
        dto.setCustomerName(ledger.getCustomer().getName());
        dto.setTransactionDate(ledger.getTransactionDate());
        dto.setTransactionType(ledger.getTransactionType());
        dto.setReferenceType(ledger.getReferenceType());
        dto.setReferenceId(ledger.getReferenceId());
        dto.setDebitAmount(ledger.getDebitAmount());
        dto.setCreditAmount(ledger.getCreditAmount());
        dto.setRunningBalance(ledger.getRunningBalance());
        dto.setDescription(ledger.getDescription());
        dto.setPaymentMode(ledger.getPaymentMode());
        dto.setCreatedAt(ledger.getCreatedAt());
        dto.setBackdated(ledger.isBackdated());
        return dto;
    }

    /**
     * Posts the reversal of a cancelled payment.
     *
     * A debit for the same amount on the same date, so the customer's balance
     * returns to what it was and the statement shows the receipt followed by its
     * cancellation. The pair nets to nothing, which is the point: a receipt that
     * was issued and then withdrawn is a fact about the account, and deleting the
     * credit outright would hide it.
     */
    @Override
    @Transactional
    public CustomerLedger reversePaymentLedgerEntry(CustomerPayment payment) {
        Customer customer = payment.getCustomer();
        BigDecimal amount = MoneyRules.money(payment.getAmount());

        logger.info("Reversing payment {} of {} for customer {}",
                payment.getId(), amount, customer.getId());

        CustomerLedger reversal = new CustomerLedger();
        reversal.setCustomer(customer);
        reversal.setTransactionDate(payment.getPaymentDate());
        reversal.setTransactionType(TransactionType.DEBIT_NOTE);
        reversal.setReferenceType("PAYMENT_REVERSAL");
        reversal.setReferenceId(payment.getId());
        reversal.setDebitAmount(amount);
        reversal.setCreditAmount(MoneyRules.money(BigDecimal.ZERO));
        reversal.setPaymentMode(payment.getPaymentMode());
        reversal.setDescription("Payment cancelled - reversal of receipt #" + payment.getId()
                + (payment.getTransactionReference() == null
                        ? "" : " (" + payment.getTransactionReference() + ")"));
        reversal.setBackdated(payment.getPaymentDate().isBefore(LocalDate.now()));
        // Set by the recalculation below; a placeholder keeps the column non-null
        // for databases where it is declared so.
        reversal.setRunningBalance(MoneyRules.money(BigDecimal.ZERO));

        CustomerLedger saved = ledgerRepository.save(reversal);

        // Re-chains from the payment's date, which both places the reversal
        // correctly in history and updates the customer's balance.
        recalculateBalancesFromDate(customer, payment.getPaymentDate());
        return saved;
    }

    // ---- statement -------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public CustomerStatementDTO getCustomerStatement(Long customerId, LocalDate startDate, LocalDate endDate) {
        logger.info("Building statement for customer {} from {} to {}", customerId, startDate, endDate);

        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("End date " + endDate + " is before start date " + startDate + ".");
        }

        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer " + customerId + " was not found."));

        boolean ranged = startDate != null && endDate != null;
        List<CustomerLedger> entries = ranged
                ? ledgerRepository.findForStatement(customer, startDate, endDate)
                : ledgerRepository.findAllForStatement(customer);

        List<CustomerLedgerDTO> rows = entries.stream().map(this::convertToDTO).collect(Collectors.toList());
        attachSaleDetail(rows);

        CustomerStatementDTO statement = new CustomerStatementDTO();
        statement.setCustomerId(customer.getId());
        statement.setCustomerName(customer.getName());
        statement.setShopName(customer.getShopName());
        statement.setMobileNo(customer.getMobileNo());
        statement.setAddress(customer.getAddress());
        statement.setCityName(customer.getCity() == null ? null : customer.getCity().getName());
        statement.setObsolete(customer.isObsolete());
        statement.setCreditLimit(customer.getCreditLimit());
        statement.setCreditLimitEnabled(customer.isCreditLimitEnabled());
        statement.setStartDate(startDate);
        statement.setEndDate(endDate);
        statement.setEntries(rows);
        statement.setGeneratedAt(LocalDateTime.now());

        if (!entries.isEmpty()) {
            statement.setFirstTransactionDate(entries.get(0).getTransactionDate());
            statement.setLastTransactionDate(entries.get(entries.size() - 1).getTransactionDate());
        }

        BigDecimal openingBalance = ranged ? balanceBefore(customer, startDate) : MoneyRules.money(BigDecimal.ZERO);
        statement.setOpeningBalance(openingBalance);
        statement.setTotals(buildTotals(rows, openingBalance));
        return statement;
    }

    /**
     * Balance carried into the statement period: the running balance on the last
     * row before startDate, which already accounts for the whole history behind
     * it.
     */
    private BigDecimal balanceBefore(Customer customer, LocalDate startDate) {
        List<CustomerLedger> previous = ledgerRepository.findLatestBefore(customer, startDate, Limit.of(1));
        return previous.isEmpty()
                ? MoneyRules.money(BigDecimal.ZERO)
                : MoneyRules.money(previous.get(0).getRunningBalance());
    }

    /**
     * Fills in birds, weight, rate, route, driver and vehicle on the SALE rows.
     *
     * A ledger row records money only, and the sale's quantities were readable
     * just as prose inside the description ("Sale - 25 birds, 45.500 kg"), which
     * cannot be aligned into columns or totalled. One batched lookup keyed by
     * reference id avoids a query per row - a full history for an active customer
     * runs to hundreds of sales.
     */
    private void attachSaleDetail(List<CustomerLedgerDTO> rows) {
        /*
         * A SALE row's quantities live on the sale - unless the sale has become a trading
         * entry, in which case they live there.
         *
         * Both are resolved, keyed on reference_type. Looking only at sales was correct
         * until route 9 moved into Trading: those 571 ledger rows now say TRADING_ENTRY,
         * and a statement for one of the eleven wholesale parties came back with birds 0,
         * weight 0.000 and rate 0.00 in every line while the money columns stayed right.
         * The balance was never wrong, but a statement claiming a customer had bought
         * nothing for 13,40,970 is not one to send.
         */
        Map<Long, Sale> sales = lookup(rows, REFERENCE_SALE,
                ids -> saleRepository.findAllById(ids), Sale::getId);
        Map<Long, TradingEntry> tradingEntries = lookup(rows, REFERENCE_TRADING_ENTRY,
                ids -> tradingEntryRepository.findAllById(ids), TradingEntry::getId);

        for (CustomerLedgerDTO row : rows) {
            if (row.getReferenceId() == null) {
                continue;
            }

            if (REFERENCE_TRADING_ENTRY.equals(row.getReferenceType())) {
                TradingEntry entry = tradingEntries.get(row.getReferenceId());
                if (entry == null) {
                    continue;
                }
                row.setBirds(entry.getBirdsSold() == null ? entry.getBirds() : entry.getBirdsSold());
                row.setWeight(entry.getKilograms() == null ? null : MoneyRules.weight(entry.getKilograms()));
                row.setRate(entry.getRate());
                row.setObsolete(entry.isObsolete());
                // No route, and that is the point of trading: a load goes out to several
                // parties without belonging to a round. The trip still names the driver
                // and the vehicle that brought it.
                TradingTrip trip = entry.getTrip();
                row.setDriverName(trip == null || trip.getDriver() == null
                        ? null : trip.getDriver().getName());
                row.setVehicleNo(entry.getVehicleNumber());
                continue;
            }

            Sale sale = sales.get(row.getReferenceId());
            if (sale == null) {
                continue;
            }
            row.setBirds(sale.getBirds());
            row.setWeight(sale.getKilograms() == null ? null : MoneyRules.weight(sale.getKilograms()));
            row.setRate(sale.getRate());
            row.setObsolete(sale.isObsolete());
            row.setRouteName(sale.getRoute() == null ? null : sale.getRoute().getName());
            row.setDriverName(sale.getDriver() == null ? null : sale.getDriver().getName());
            row.setVehicleNo(sale.getVehicleNo() == null ? null : String.valueOf(sale.getVehicleNo()));
        }
    }

    private static final String REFERENCE_SALE = "SALE";
    private static final String REFERENCE_TRADING_ENTRY = "TRADING_ENTRY";

    /**
     * One batched lookup for the rows of a given reference type.
     *
     * Batched rather than a query per row, because a full history for an active customer
     * runs to hundreds of lines - customer 67 has 499.
     */
    private <T> Map<Long, T> lookup(List<CustomerLedgerDTO> rows,
                                    String referenceType,
                                    Function<List<Long>, List<T>> fetch,
                                    Function<T, Long> idOf) {
        List<Long> ids = rows.stream()
                .filter(row -> row.getTransactionType() == TransactionType.SALE)
                .filter(row -> referenceType.equals(row.getReferenceType()))
                .map(CustomerLedgerDTO::getReferenceId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        if (ids.isEmpty()) {
            return Map.of();
        }
        return fetch.apply(ids).stream()
                .collect(Collectors.toMap(idOf, item -> item, (first, second) -> first));
    }

    private LedgerStatementTotals buildTotals(List<CustomerLedgerDTO> rows, BigDecimal openingBalance) {
        LedgerStatementTotals totals = new LedgerStatementTotals();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        BigDecimal weight = BigDecimal.ZERO;
        long birds = 0;

        for (CustomerLedgerDTO row : rows) {
            debit = debit.add(MoneyRules.money(row.getDebitAmount()));
            credit = credit.add(MoneyRules.money(row.getCreditAmount()));

            if (row.getTransactionType() == TransactionType.SALE) {
                totals.setSaleCount(totals.getSaleCount() + 1);
                birds += row.getBirds() == null ? 0 : row.getBirds();
                weight = weight.add(MoneyRules.weight(row.getWeight()));
            } else if (row.getTransactionType() == TransactionType.PAYMENT) {
                totals.setPaymentCount(totals.getPaymentCount() + 1);
            } else if (row.getTransactionType() != TransactionType.OPENING_BALANCE) {
                totals.setAdjustmentCount(totals.getAdjustmentCount() + 1);
            }
        }

        totals.setRowCount(rows.size());
        totals.setTotalDebit(MoneyRules.money(debit));
        totals.setTotalCredit(MoneyRules.money(credit));
        totals.setNetMovement(MoneyRules.money(debit.subtract(credit)));
        totals.setOpeningBalance(MoneyRules.money(openingBalance));
        totals.setBirds(birds);
        totals.setWeight(MoneyRules.weight(weight));
        totals.setAverageRate(weight.signum() == 0
                ? BigDecimal.ZERO.setScale(2)
                : debit.divide(weight, 2, RoundingMode.HALF_UP));

        // The closing balance is the last row's running balance rather than
        // opening plus movement: an opening-balance row falling inside the period
        // already carries a brought-forward figure, and reading the last row keeps
        // the statement agreeing with the ledger wherever the two would differ.
        totals.setClosingBalance(rows.isEmpty()
                ? MoneyRules.money(openingBalance)
                : MoneyRules.money(rows.get(rows.size() - 1).getRunningBalance()));
        return totals;
    }
}
