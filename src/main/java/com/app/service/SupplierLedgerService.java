package com.app.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.entity.Purchase;
import com.app.entity.Supplier;
import com.app.entity.SupplierLedger;
import com.app.entity.SupplierLedger.TransactionType;
import com.app.entity.SupplierPaymentHist;
import com.app.repository.SupplierLedgerRepository;
import com.app.repository.SupplierRepository;
import com.app.utility.MoneyRules;

/**
 * Keeps the supplier ledger, and the payable that comes out of it.
 *
 * The purchase side's LedgerServiceImpl, and written against the same rules, because the
 * mistakes available here are the ones that side already made and fixed: a balance that is
 * stored instead of derived, and a backdated entry that leaves every later balance wrong.
 *
 * <p>What this replaces wrote the payable as {@code supplier.setPendingPayment(total)} after
 * accumulating it - so each purchase overwrote the outstanding amount with its own total, and
 * 12,05,020 of what the business owed simply was not recorded anywhere.
 *
 * <p>One direction to keep in mind: we owe a supplier, so a purchase <em>raises</em> the
 * balance and is the credit; a payment lowers it and is the debit. That is the reverse of the
 * customer ledger, where a sale raises what they owe us and is the debit.
 */
@Service
public class SupplierLedgerService {

    private static final Logger logger = LoggerFactory.getLogger(SupplierLedgerService.class);

    private final SupplierLedgerRepository ledgerRepository;
    private final SupplierRepository supplierRepository;

    public SupplierLedgerService(SupplierLedgerRepository ledgerRepository,
                                 SupplierRepository supplierRepository) {
        this.ledgerRepository = ledgerRepository;
        this.supplierRepository = supplierRepository;
    }

    /**
     * Posts a purchase to its supplier's account.
     *
     * Idempotent on the purchase id, so a retry after a half-failed save cannot bill the same
     * load twice - the thing a payables ledger must never do.
     */
    @Transactional
    public SupplierLedger recordPurchase(Purchase purchase) {
        if (purchase.getSupplier() == null) {
            throw new IllegalStateException("Purchase " + purchase.getId()
                    + " has no supplier, so there is no account to bill it to.");
        }

        SupplierLedger existing = findByReference("PURCHASE", purchase.getId());
        if (existing != null) {
            logger.debug("Purchase {} is already on the ledger as row {}",
                    purchase.getId(), existing.getId());
            return existing;
        }

        SupplierLedger row = new SupplierLedger();
        row.setSupplier(purchase.getSupplier());
        row.setTransactionDate(purchase.getEntryDate());
        row.setTransactionType(TransactionType.PURCHASE);
        row.setReferenceType("PURCHASE");
        row.setReferenceId(purchase.getId());
        // A purchase is the credit: it raises what we owe.
        row.setCreditAmount(MoneyRules.money(purchase.getTotalAmount()));
        row.setDebitAmount(BigDecimal.ZERO);
        row.setDescription(describe(purchase));

        return post(row);
    }

    /** Posts a payment made to a supplier. Reduces what we owe. */
    @Transactional
    public SupplierLedger recordPayment(SupplierPaymentHist payment) {
        if (payment.getSupplier() == null) {
            throw new IllegalStateException("Payment " + payment.getId()
                    + " has no supplier, so there is no account to credit it to.");
        }

        SupplierLedger existing = findByReference("SUPPLIER_PAYMENT", payment.getId());
        if (existing != null) {
            logger.debug("Supplier payment {} is already on the ledger as row {}",
                    payment.getId(), existing.getId());
            return existing;
        }

        SupplierLedger row = new SupplierLedger();
        row.setSupplier(payment.getSupplier());
        row.setTransactionDate(payment.getDateOfTransaction() == null
                ? payment.getDateOfPurchase()
                : payment.getDateOfTransaction());
        row.setTransactionType(TransactionType.PAYMENT);
        row.setReferenceType("SUPPLIER_PAYMENT");
        row.setReferenceId(payment.getId());
        row.setDebitAmount(MoneyRules.money(payment.getPaidAmount()));
        row.setCreditAmount(BigDecimal.ZERO);
        row.setDescription(payment.getTrans_id() == null || payment.getTrans_id().isBlank()
                ? "Payment"
                : "Payment ref " + payment.getTrans_id().trim());

        return post(row);
    }

    /**
     * Writes one row, then makes the balances right.
     *
     * A row dated on or after everything present only needs the previous balance. A backdated
     * one invalidates every balance after it, so those are read back and rewritten - the
     * alternative is a statement whose closing figure disagrees with its own lines.
     */
    private SupplierLedger post(SupplierLedger row) {
        if (row.getTransactionDate() == null) {
            throw new IllegalArgumentException("A ledger row must have a date.");
        }

        BigDecimal before = balanceOn(row.getSupplier().getId(), row.getTransactionDate());
        row.setRunningBalance(MoneyRules.money(
                before.add(row.getCreditAmount()).subtract(row.getDebitAmount())));

        boolean backdated = hasRowsAfter(row.getSupplier().getId(), row.getTransactionDate());
        row.setBackdated(backdated);

        SupplierLedger saved = ledgerRepository.save(row);

        if (backdated) {
            logger.info("Backdated supplier entry on {}. Recalculating supplier {} from there.",
                    saved.getTransactionDate(), saved.getSupplier().getId());
            recalculateFrom(saved.getSupplier().getId(), saved.getTransactionDate());
        } else {
            updatePayable(saved.getSupplier(), saved.getRunningBalance());
        }
        return saved;
    }

    /**
     * Rewrites every running balance from a date onwards.
     *
     * Walks the rows in statement order and re-derives each balance from the one before, so a
     * backdated purchase does not leave the account telling two different stories.
     */
    @Transactional
    public BigDecimal recalculateFrom(Long supplierId, LocalDate from) {
        BigDecimal opening = ledgerRepository.balanceBefore(supplierId, from);
        BigDecimal balance = opening == null ? BigDecimal.ZERO : opening;

        List<SupplierLedger> rows = ledgerRepository.findFromDate(supplierId, from);
        for (SupplierLedger row : rows) {
            balance = MoneyRules.money(
                    balance.add(row.getCreditAmount()).subtract(row.getDebitAmount()));
            row.setRunningBalance(balance);
            ledgerRepository.save(row);
        }

        logger.info("Supplier {} recalculated over {} row(s). Payable is now {}",
                supplierId, rows.size(), balance);

        BigDecimal closing = balance;
        supplierRepository.findById(supplierId)
                .ifPresent(supplier -> updatePayable(supplier, closing));
        return closing;
    }

    /** What we owe this supplier now, from the ledger rather than the stored column. */
    public BigDecimal currentPayable(Long supplierId) {
        List<SupplierLedger> latest = ledgerRepository.findLatest(supplierId, Limit.of(1));
        return latest.isEmpty() ? BigDecimal.ZERO : MoneyRules.money(latest.get(0).getRunningBalance());
    }

    /** The balance as at the end of a date, for a new row landing on that date. */
    private BigDecimal balanceOn(Long supplierId, LocalDate date) {
        BigDecimal before = ledgerRepository.balanceBefore(supplierId, date.plusDays(1));
        return before == null ? BigDecimal.ZERO : before;
    }

    private boolean hasRowsAfter(Long supplierId, LocalDate date) {
        List<SupplierLedger> latest = ledgerRepository.findLatest(supplierId, Limit.of(1));
        return !latest.isEmpty() && latest.get(0).getTransactionDate().isAfter(date);
    }

    private SupplierLedger findByReference(String referenceType, Long referenceId) {
        if (referenceId == null) {
            return null;
        }
        return ledgerRepository.findAll().stream()
                .filter(row -> referenceType.equals(row.getReferenceType())
                        && referenceId.equals(row.getReferenceId()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Keeps supplier.pending_payment in step with the ledger.
     *
     * The column stays because other screens read it, but it is now a copy of a derived
     * figure rather than the figure itself. Everything that needs to be right reads the
     * ledger; this is here so an old screen does not show a number from before the change.
     */
    private void updatePayable(Supplier supplier, BigDecimal balance) {
        supplier.setPendingPayment(MoneyRules.money(balance));
        supplierRepository.save(supplier);
    }

    private static String describe(Purchase purchase) {
        StringBuilder text = new StringBuilder("Purchase #").append(purchase.getId());
        if (purchase.getFarm() != null && !purchase.getFarm().isBlank()) {
            text.append(" - ").append(purchase.getFarm().trim());
        }
        return text.length() > 500 ? text.substring(0, 500) : text.toString();
    }
}
