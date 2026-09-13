package com.app.dto.purchase;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One supplier on the payables list.
 *
 * The purchase side's answer to TradingPartyRow, and the figure it exists to show is
 * {@code outstanding} - which until now was not recorded anywhere. supplier.pending_payment
 * held the last purchase's total rather than the balance, so the business's own payables were
 * understated by 12,05,020 across three suppliers.
 *
 * @param bought      billed by this supplier over the period, or all time when unbounded
 * @param paid        money that has gone back to them
 * @param outstanding what we owe now, from the ledger's last running balance - deliberately
 *                    not bought minus paid over the period, because a balance is not a
 *                    question about a date range
 * @param averageRate paid per kilogram over the period, the number that says whether a
 *                    supplier is getting dearer
 */
public record SupplierPayableRow(
        Long supplierId,
        String name,
        String branch,
        int purchases,
        long birds,
        BigDecimal kilograms,
        BigDecimal bought,
        BigDecimal paid,
        BigDecimal outstanding,
        BigDecimal averageRate,
        LocalDate lastPurchase,
        LocalDate lastPayment,
        /** True when the stored column disagrees with the ledger - a bug worth seeing. */
        boolean payableDisagrees) {
}
