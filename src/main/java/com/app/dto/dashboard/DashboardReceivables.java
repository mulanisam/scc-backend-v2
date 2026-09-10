package com.app.dto.dashboard;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * The receivable book.
 *
 * These are deliberately not aging buckets. Nothing in this system allocates a
 * payment to a particular sale, so how old a balance is cannot be derived from
 * what is recorded, and a bucket labelled "60-90 days" would be invented. The
 * buckets are by size of balance, and staleness is expressed as days since the
 * customer last paid anything - both of which are answerable.
 */
@Data
public class DashboardReceivables {

    private BigDecimal totalOutstanding;
    private long customersWithBalance;
    private long customersInCredit;

    private long countOver50k;
    private BigDecimal amountOver50k;
    private long countOver20k;
    private BigDecimal amountOver20k;

    private long countOverCreditLimit;
    private BigDecimal amountOverCreditLimit;

    /** Owed by customers who have not paid anything in 30 days or more. */
    private long countStale30Days;
    private BigDecimal amountStale30Days;

    private List<DashboardCustomerBalanceRow> topDebtors;
}
