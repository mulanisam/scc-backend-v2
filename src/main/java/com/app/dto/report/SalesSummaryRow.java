package com.app.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One aggregated bucket in a summary report: a period crossed with a business
 * dimension, for example "week of 2026-09-07, route Madha".
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesSummaryRow {

    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String periodLabel;

    private Long dimensionId;
    private String dimensionName;

    /** Second dimension, when the report crosses two of them. */
    private Long dimension2Id;
    private String dimension2Name;

    private long transactionCount;
    private long customerCount;

    private long birds;
    private BigDecimal weight;
    private BigDecimal amount;
    private BigDecimal payment;

    /** Unpaid amount arising from this period's own sales. */
    private BigDecimal pending;

    /** Average realised rate for the bucket: amount / weight. */
    private BigDecimal averageRate;

    /**
     * What was still owed at the end of this period, from the ledger's running
     * balance - not the sum of this period's pending. This is the figure that
     * shows a customer's debt creeping up across months.
     */
    private BigDecimal closingBalance;
}
