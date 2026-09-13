package com.app.service.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.purchase.SupplierAccount;
import com.app.dto.purchase.SupplierPayableRow;
import com.app.entity.DcDetail;
import com.app.entity.Purchase;
import com.app.entity.Supplier;
import com.app.entity.SupplierLedger;
import com.app.exception.ResourceNotFoundException;
import com.app.repository.PurchaseRepository;
import com.app.repository.SupplierLedgerRepository;
import com.app.repository.SupplierRepository;
import com.app.service.SupplierLedgerService;
import com.app.utility.MoneyRules;

/**
 * What the business has bought and what it still owes for it.
 *
 * The purchase side had no reports at all. Nine purchases worth 17,44,540 were recorded and
 * the only way to see any of it was to read the table - which is part of how a payable
 * understated by 12,05,020 went unnoticed, and how a purchase of 960 birds for 0 has sat in
 * the data since October.
 *
 * <p>Built to match TradingReportService: the period bounds the activity columns and never the
 * balance, because "what do we owe" is not a question about a date range. Outstanding always
 * comes from the ledger's last running balance.
 */
@Service
public class PurchaseReportService {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseReportService.class);

    private final PurchaseRepository purchaseRepository;
    private final SupplierRepository supplierRepository;
    private final SupplierLedgerRepository ledgerRepository;
    private final SupplierLedgerService ledgerService;

    public PurchaseReportService(PurchaseRepository purchaseRepository,
                                 SupplierRepository supplierRepository,
                                 SupplierLedgerRepository ledgerRepository,
                                 SupplierLedgerService ledgerService) {
        this.purchaseRepository = purchaseRepository;
        this.supplierRepository = supplierRepository;
        this.ledgerRepository = ledgerRepository;
        this.ledgerService = ledgerService;
    }

    /**
     * Every supplier we have bought from, most owed first.
     *
     * Ordered by what is outstanding rather than alphabetically, because the list exists to
     * answer "who needs paying" - and the supplier owed 7,62,500 should not be below one owed
     * nothing because of their name.
     *
     * @param from bounds the activity columns only; null means all time
     */
    @Transactional(readOnly = true)
    public List<SupplierPayableRow> payables(LocalDate from, LocalDate to) {
        List<SupplierPayableRow> rows = new ArrayList<>();

        for (Supplier supplier : supplierRepository.findAll()) {
            if (supplier.isObsolete()) {
                continue;
            }

            List<Purchase> purchases = purchaseRepository
                    .findBySupplierIdOrderByEntryDateDescIdDesc(supplier.getId())
                    .stream()
                    .filter(purchase -> within(purchase.getEntryDate(), from, to))
                    .toList();

            List<SupplierLedger> ledgerRows = ledgerRepository
                    .findForSupplier(supplier.getId(), from, to);

            // A supplier with no history at all is left off: the payables list is not the
            // supplier master, and fifteen empty rows would bury the four that matter.
            if (purchases.isEmpty() && ledgerRows.isEmpty()) {
                continue;
            }

            long birds = 0;
            BigDecimal kilograms = BigDecimal.ZERO;
            BigDecimal bought = BigDecimal.ZERO;
            for (Purchase purchase : purchases) {
                bought = bought.add(MoneyRules.money(purchase.getTotalAmount()));
                for (DcDetail line : purchase.getDcDetails()) {
                    birds += line.getNos() == null ? 0 : line.getNos();
                    kilograms = kilograms.add(weight(line.getKilograms()));
                }
            }

            BigDecimal paid = ledgerRows.stream()
                    .map(SupplierLedger::getDebitAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal outstanding = ledgerService.currentPayable(supplier.getId());

            rows.add(new SupplierPayableRow(
                    supplier.getId(),
                    supplier.getName(),
                    supplier.getBranch(),
                    purchases.size(),
                    birds,
                    weight(kilograms),
                    MoneyRules.money(bought),
                    MoneyRules.money(paid),
                    outstanding,
                    averageRate(bought, kilograms),
                    purchases.stream().map(Purchase::getEntryDate)
                            .filter(java.util.Objects::nonNull)
                            .max(Comparator.naturalOrder()).orElse(null),
                    ledgerRows.stream()
                            .filter(row -> row.getTransactionType() == SupplierLedger.TransactionType.PAYMENT)
                            .map(SupplierLedger::getTransactionDate)
                            .max(Comparator.naturalOrder()).orElse(null),
                    // The stored column is kept in step by the ledger service, so a
                    // disagreement means something wrote it directly. Surfaced rather than
                    // hidden, because that is exactly the bug this replaced.
                    MoneyRules.money(supplier.getPendingPayment()).compareTo(outstanding) != 0));
        }

        rows.sort(Comparator.comparing(SupplierPayableRow::outstanding).reversed());
        logger.info("Payables list: {} supplier(s), {} to {}", rows.size(), from, to);
        return rows;
    }

    /**
     * One supplier's account and the purchases behind it.
     *
     * @param from null for the whole account
     */
    @Transactional(readOnly = true)
    public SupplierAccount account(Long supplierId, LocalDate from, LocalDate to) {
        Supplier supplier = supplierRepository.findById(supplierId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Supplier " + supplierId + " was not found."));

        List<SupplierLedger> ledgerRows = ledgerRepository.findForSupplier(supplierId, from, to);

        BigDecimal opening = from == null
                ? BigDecimal.ZERO
                : MoneyRules.money(ledgerRepository.balanceBefore(supplierId, from));

        List<SupplierAccount.Row> rows = ledgerRows.stream()
                .map(row -> new SupplierAccount.Row(
                        row.getTransactionDate(),
                        row.getTransactionType().name(),
                        row.getDescription(),
                        row.getReferenceType(),
                        row.getReferenceId(),
                        MoneyRules.money(row.getCreditAmount()),
                        MoneyRules.money(row.getDebitAmount()),
                        MoneyRules.money(row.getRunningBalance()),
                        row.getPaymentMode(),
                        row.isBackdated()))
                .toList();

        List<Purchase> purchases = purchaseRepository
                .findBySupplierIdOrderByEntryDateDescIdDesc(supplierId)
                .stream()
                .filter(purchase -> within(purchase.getEntryDate(), from, to))
                .toList();

        List<SupplierAccount.PurchaseSummary> summaries = new ArrayList<>();
        long totalBirds = 0;
        BigDecimal totalKilograms = BigDecimal.ZERO;
        BigDecimal totalBought = BigDecimal.ZERO;
        BigDecimal totalExpenses = BigDecimal.ZERO;

        for (Purchase purchase : purchases) {
            long birds = 0;
            BigDecimal kilograms = BigDecimal.ZERO;
            List<SupplierAccount.DcLine> lines = new ArrayList<>();

            for (DcDetail line : purchase.getDcDetails()) {
                birds += line.getNos() == null ? 0 : line.getNos();
                kilograms = kilograms.add(weight(line.getKilograms()));
                lines.add(new SupplierAccount.DcLine(
                        line.getId(),
                        line.getDcNo(),
                        line.getNos(),
                        weight(line.getKilograms()),
                        MoneyRules.money(line.getRate()),
                        MoneyRules.money(line.getAmount()),
                        line.getFilePath() != null && !line.getFilePath().isBlank()));
            }

            BigDecimal amount = MoneyRules.money(purchase.getTotalAmount());
            BigDecimal paid = MoneyRules.money(purchase.getPaidAmount());
            BigDecimal expenses = MoneyRules.money(purchase.getDriverExpense())
                    .add(MoneyRules.money(purchase.getDiesel()))
                    .add(MoneyRules.money(purchase.getHamali()));

            summaries.add(new SupplierAccount.PurchaseSummary(
                    purchase.getId(),
                    purchase.getEntryDate(),
                    purchase.getFarm(),
                    purchase.getBranch(),
                    // Read inside the transaction: both are lazy, and reading them in the
                    // controller would throw with open-in-view off.
                    purchase.getVehicle() == null ? null : purchase.getVehicle().getVehicleNo(),
                    purchase.getDriver() == null ? null : purchase.getDriver().getName(),
                    lines.size(),
                    birds,
                    weight(kilograms),
                    averageRate(amount, kilograms),
                    amount,
                    paid,
                    MoneyRules.money(amount.subtract(paid)),
                    expenses,
                    lines));

            totalBirds += birds;
            totalKilograms = totalKilograms.add(kilograms);
            totalBought = totalBought.add(amount);
            totalExpenses = totalExpenses.add(expenses);
        }

        BigDecimal totalPaid = ledgerRows.stream()
                .map(SupplierLedger::getDebitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        SupplierAccount.Totals totals = new SupplierAccount.Totals(
                summaries.size(),
                totalBirds,
                weight(totalKilograms),
                MoneyRules.money(totalBought),
                MoneyRules.money(totalPaid),
                ledgerService.currentPayable(supplierId),
                averageRate(totalBought, totalKilograms),
                MoneyRules.money(totalExpenses));

        logger.info("Supplier account for {} ({}): {} purchase(s), {} ledger row(s)",
                supplier.getName(), supplierId, summaries.size(), rows.size());

        return new SupplierAccount(supplierId, supplier.getName(), supplier.getBranch(),
                from, to, opening, rows, summaries, totals);
    }

    /**
     * Amount over weight, to two places.
     *
     * Derived rather than averaged from the line rates: a purchase taken at two different
     * rates has no single line rate, and the honest figure for the load is the total over the
     * total. Null when there is no weight - a rate per zero kilograms is not zero, it is
     * nothing, and printing 0.00 next to a real purchase reads as a rate of zero.
     */
    private static BigDecimal averageRate(BigDecimal amount, BigDecimal kilograms) {
        if (kilograms == null || kilograms.signum() == 0) {
            return null;
        }
        return MoneyRules.money(amount).divide(kilograms, 2, RoundingMode.HALF_UP);
    }

    /** Weight keeps three decimals, the precision the DC notes are written in. */
    private static BigDecimal weight(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(3)
                : value.setScale(3, RoundingMode.HALF_UP);
    }

    private static boolean within(LocalDate date, LocalDate from, LocalDate to) {
        if (date == null) {
            // A purchase with no date cannot be placed in a period. Kept in the unbounded
            // view so it is visible and can be fixed, rather than quietly absent everywhere.
            return from == null && to == null;
        }
        if (from != null && date.isBefore(from)) {
            return false;
        }
        return to == null || !date.isAfter(to);
    }
}
