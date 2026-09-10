package com.app.dto;

import java.math.BigDecimal;

import lombok.Data;

/**
 * Footer figures for a customer statement.
 *
 * Debits and credits are summed over the statement period only; the closing
 * balance is the running balance carried by the last row, which already includes
 * everything that happened before the period. That distinction is why the
 * statement shows an opening balance line rather than starting from zero.
 */
@Data
public class LedgerStatementTotals {

    private int rowCount;
    private int saleCount;
    private int paymentCount;
    private int adjustmentCount;

    private BigDecimal totalDebit;
    private BigDecimal totalCredit;
    private BigDecimal netMovement;

    private BigDecimal openingBalance;
    private BigDecimal closingBalance;

    /** Birds and weight sold within the period, from the referenced sales. */
    private long birds;
    private BigDecimal weight;
    /** totalDebit / weight - the realised rate per kilogram for the period. */
    private BigDecimal averageRate;
}
