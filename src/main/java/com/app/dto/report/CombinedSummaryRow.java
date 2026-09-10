package com.app.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/**
 * Bought against sold for one period.
 *
 * Weight bought minus weight sold is the only place shrinkage can actually be
 * measured in this system: a sale trip records the birds loaded but never their
 * weight, and no column links a trip to the purchase it came from. Comparing the
 * two sides per period is therefore the closest available answer, and it is only
 * meaningful when both sides are actually recorded - hence the coverage fields.
 */
@Data
public class CombinedSummaryRow {

    private LocalDate periodStart;
    private LocalDate periodEnd;
    private String periodLabel;

    // ---- bought ----------------------------------------------------------
    private long purchaseCount;
    private long birdsBought;
    private BigDecimal weightBought;
    private BigDecimal amountBought;
    /** Rate paid per kilogram: amountBought / weightBought. */
    private BigDecimal buyRatePerKg;
    /** Diesel + driver expense + hamali on the purchase side. */
    private BigDecimal purchaseExpenses;
    private BigDecimal paidToSuppliers;
    /** Still owed to suppliers: amountBought - paidToSuppliers. */
    private BigDecimal owedToSuppliers;

    // ---- sold ------------------------------------------------------------
    private long saleCount;
    private long birdsSold;
    private BigDecimal weightSold;
    private BigDecimal amountSold;
    /** Rate realised per kilogram: amountSold / weightSold. */
    private BigDecimal sellRatePerKg;
    private BigDecimal recoveredFromCustomers;
    /** Still owed by customers for this period's sales. */
    private BigDecimal pendingFromCustomers;

    // ---- comparison ------------------------------------------------------
    /** birdsBought - birdsSold. */
    private long birdVariance;

    /**
     * weightBought - weightSold: shrinkage between farm and customer.
     * Only meaningful when both sides were recorded for the period.
     */
    private BigDecimal weightLoss;
    private BigDecimal weightLossPercent;

    /** amountSold - amountBought, before purchase expenses. */
    private BigDecimal grossMargin;
    /** grossMargin - purchaseExpenses. */
    private BigDecimal netMargin;
    /** sellRatePerKg - buyRatePerKg. */
    private BigDecimal marginPerKg;
    /** grossMargin as a percentage of amountBought. */
    private BigDecimal marginPercent;

    /**
     * False when one side of the period has no rows at all, which makes every
     * comparison figure above meaningless rather than merely zero. The report
     * surfaces this instead of presenting an apparent margin as fact.
     */
    private boolean comparable;
}
