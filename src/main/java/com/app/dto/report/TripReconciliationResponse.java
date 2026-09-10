package com.app.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * Trip reconciliation over a date range: every trip, plus the totals across
 * them and a count of how many failed to balance.
 */
@Data
public class TripReconciliationResponse {

    private LocalDate startDate;
    private LocalDate endDate;
    private List<String> appliedFilters;

    private List<TripReconciliationRow> trips;

    // ---- totals across every trip in the range ---------------------------
    private long tripCount;
    private long birdsLoaded;
    private long birdsSold;
    private long mortality;
    private long returnToFarm;
    private long birdVariance;

    private BigDecimal weightLoaded;
    private BigDecimal weightSold;

    /** Only over trips where the loaded weight is known. */
    private BigDecimal weightLoss;
    private BigDecimal weightLossPercent;
    /** How many trips contributed to the weight-loss figure. */
    private long tripsWithLoadedWeight;

    private BigDecimal amount;
    private BigDecimal paid;
    private BigDecimal pending;
    private BigDecimal averageRate;
    private BigDecimal mortalityPercent;

    // ---- data quality ----------------------------------------------------
    /** Trips whose bird counts do not balance. */
    private long unbalancedTripCount;
    /** Trips whose header weight disagrees with the sum of their lines. */
    private long headerMismatchCount;
    /** Trips flagged as corrections. */
    private long correctionCount;
}
