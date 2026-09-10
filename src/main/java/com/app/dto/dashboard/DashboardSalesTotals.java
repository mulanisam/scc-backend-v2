package com.app.dto.dashboard;

import java.math.BigDecimal;

import lombok.Data;

/** Sales side of one window: a day, a month to date, a year to date. */
@Data
public class DashboardSalesTotals {

    private String label;
    private long tripCount;
    private long saleCount;
    private long customerCount;

    private long birdsSold;
    private BigDecimal weightSold;
    private BigDecimal amount;
    private BigDecimal received;
    /** Billed but not collected within the window - not the ledger balance. */
    private BigDecimal pending;
    /** amount / weightSold. */
    private BigDecimal averageRate;

    /** Trip-level figures, which the sale rows do not carry. */
    private long birdsLoaded;
    private long mortality;
    private long returnToFarm;
    private BigDecimal weightLoaded;
    /** weightLoaded - weightSold, only meaningful where loaded weight was recorded. */
    private BigDecimal weightGap;
    /** Share of the amount that was collected, as a percentage. */
    private BigDecimal recoveryPercent;
}
