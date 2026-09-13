package com.app.dto.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One supplier's account: what was bought, what was paid, and what remains.
 *
 * Two views of the same period, deliberately. The ledger rows are the account - purchases and
 * payments in the order they happened, each with the balance after it - and the purchase list
 * is the detail behind the PURCHASE rows, with the DC lines that made each one up. The first
 * answers "what do we owe and how did it get there"; the second answers "what did we actually
 * receive for it", and a supplier query needs both open at once.
 */
public record SupplierAccount(
        Long supplierId,
        String name,
        String branch,
        LocalDate from,
        LocalDate to,
        /** What was owed before the period opened. */
        BigDecimal openingBalance,
        List<Row> rows,
        List<PurchaseSummary> purchases,
        Totals totals) {

    /**
     * One movement on the account.
     *
     * @param purchased raises what we owe
     * @param paid      reduces it
     * @param balance   what was owed after this row
     */
    public record Row(
            LocalDate date,
            String type,
            String description,
            String referenceType,
            Long referenceId,
            BigDecimal purchased,
            BigDecimal paid,
            BigDecimal balance,
            String paymentMode,
            /** True when this row was entered after later ones, so balances were rewritten. */
            boolean backdated) {
    }

    /**
     * One purchase, with enough on it to check the bill without opening it.
     *
     * Birds and weight are here because the rate is what a purchase argument is usually
     * about, and a rate is only meaningful beside the weight it was paid on.
     */
    public record PurchaseSummary(
            Long id,
            LocalDate date,
            String farm,
            String branch,
            String vehicleNo,
            String driverName,
            int dcLines,
            long birds,
            BigDecimal kilograms,
            BigDecimal rate,
            BigDecimal amount,
            BigDecimal paid,
            BigDecimal outstanding,
            /** Diesel, hamali and the driver's expense - captured on entry, never reported. */
            BigDecimal tripExpenses,
            List<DcLine> lines) {
    }

    /** One DC note on a purchase. */
    public record DcLine(
            Long id,
            String dcNo,
            Integer birds,
            BigDecimal kilograms,
            BigDecimal rate,
            BigDecimal amount,
            /** True when a scan of the DC was attached. */
            boolean hasScan) {
    }

    /**
     * @param outstanding what we owe now, not bought minus paid over the period - the two are
     *                    different whenever the period does not start at the account's opening
     */
    public record Totals(
            int purchases,
            long birds,
            BigDecimal kilograms,
            BigDecimal bought,
            BigDecimal paid,
            BigDecimal outstanding,
            BigDecimal averageRate,
            BigDecimal tripExpenses) {
    }
}
