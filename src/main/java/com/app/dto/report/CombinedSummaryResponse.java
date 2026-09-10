package com.app.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * Bought against sold over a date range, per period, with the totals across it.
 */
@Data
public class CombinedSummaryResponse {

    private LocalDate startDate;
    private LocalDate endDate;
    private String periodLabel;

    private List<CombinedSummaryRow> periods;

    // ---- totals ----------------------------------------------------------
    private long purchaseCount;
    private long birdsBought;
    private BigDecimal weightBought;
    private BigDecimal amountBought;
    private BigDecimal buyRatePerKg;
    private BigDecimal purchaseExpenses;
    private BigDecimal paidToSuppliers;
    private BigDecimal owedToSuppliers;

    private long saleCount;
    private long birdsSold;
    private BigDecimal weightSold;
    private BigDecimal amountSold;
    private BigDecimal sellRatePerKg;
    private BigDecimal recoveredFromCustomers;
    private BigDecimal pendingFromCustomers;

    private long birdVariance;
    private BigDecimal weightLoss;
    private BigDecimal weightLossPercent;
    private BigDecimal grossMargin;
    private BigDecimal netMargin;
    private BigDecimal marginPerKg;
    private BigDecimal marginPercent;

    // ---- how much of this can be trusted ---------------------------------
    /** Periods where both sides have rows, so the comparison means something. */
    private long comparablePeriods;
    /** Periods with sales but no purchases recorded. */
    private long periodsMissingPurchases;
    /** Periods with purchases but no sales recorded. */
    private long periodsMissingSales;

    /**
     * Birds sold as a multiple of birds bought across the whole range. A figure
     * far above 1 means the purchase side is not being recorded, and every margin
     * above is inflated by exactly that gap.
     */
    private BigDecimal soldToBoughtRatio;

    /** Plain-language warning when the two sides are not comparable. */
    private String coverageWarning;
}
