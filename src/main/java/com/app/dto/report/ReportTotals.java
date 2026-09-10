package com.app.dto.report;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Column totals for a report.
 *
 * The previous reports had none, which made it impossible to answer questions
 * like "total birds and weight for this route this week" - the figure had to be
 * added up by hand from the rows.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReportTotals {

    private long rowCount;
    private long transactionCount;
    private long birds;
    private BigDecimal weight;
    private BigDecimal amount;
    private BigDecimal payment;
    private BigDecimal pending;

    /** Average realised rate across the report: amount / weight. */
    private BigDecimal averageRate;
}
