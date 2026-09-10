package com.app.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One sale transaction in a detail report.
 *
 * Typed, unlike the previous LinkedHashMap rows keyed by display strings such as
 * "PAYMENT PENDING", where renaming a column silently blanked it.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SalesDetailRow {

    private Long saleId;
    private LocalDate date;

    private String route;
    private String city;
    private String customer;
    private String shopName;
    private String driver;
    private String vehicle;

    private Integer birds;
    private BigDecimal weight;
    private BigDecimal rate;
    private BigDecimal amount;
    private BigDecimal payment;
    private BigDecimal pending;

    /**
     * The customer's ledger balance immediately after this sale. Taken from
     * customer_ledger rather than sale.balance_pending, which is a denormalised
     * running column that goes stale whenever history is corrected.
     */
    private BigDecimal balanceAfter;

    private String paymentMode;
    private String description;

    /** Who recorded the entry, once auditing has data for it. */
    private String createdBy;
}
