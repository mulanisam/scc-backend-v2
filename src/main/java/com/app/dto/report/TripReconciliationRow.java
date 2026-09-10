package com.app.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/**
 * One trip, reconciled end to end.
 *
 * Answers, for a single vehicle load: how many birds went out, what happened to
 * every one of them, how much weight and money came back, what is still owed, and
 * whether the figures actually add up.
 */
@Data
public class TripReconciliationRow {

    private Long tripId;
    private LocalDate date;
    private String route;
    private String vehicle;
    private String driver;

    // ---- birds: loaded, then accounted for -------------------------------
    /** Birds loaded at the farm. */
    private Integer birdsLoaded;
    /** Birds sold, summed from the trip's own sale lines. */
    private long birdsSold;
    private Integer mortality;
    /** Birds returned to the farm - stock going back. */
    private Integer returnToFarm;

    /**
     * birdsLoaded - (birdsSold + mortality + returnToFarm).
     * Positive means birds are unaccounted for; negative means more were
     * distributed than were recorded as loaded.
     */
    private long birdVariance;
    private boolean birdsBalanced;

    // ---- weight ----------------------------------------------------------
    /** Weight loaded at the farm, where it was recorded. Null otherwise. */
    private BigDecimal weightLoaded;
    /** Weight sold, summed from the trip's sale lines. */
    private BigDecimal weightSold;

    /**
     * weightLoaded - weightSold: shrinkage in transit.
     *
     * Null when weightLoaded was never captured, which is the case for every
     * trip recorded before the field existed. Null means unknown, not zero.
     */
    private BigDecimal weightLoss;
    private BigDecimal weightLossPercent;

    /**
     * The trip header's own weight total against the sum of its lines. A
     * non-zero value means the header and its detail disagree - a data fault,
     * distinct from real shrinkage.
     */
    private BigDecimal headerWeightVariance;

    /** Average weight per bird sold, a useful sanity check on the load. */
    private BigDecimal averageWeightPerBird;

    // ---- money -----------------------------------------------------------
    private BigDecimal amount;
    private BigDecimal paid;
    /** Unpaid amount arising from this trip. */
    private BigDecimal pending;
    /** Realised rate for the trip: amount / weight sold. */
    private BigDecimal averageRate;

    /**
     * Combined closing ledger balance of the customers on this trip, as at the
     * trip date - what they owed in total after it.
     */
    private BigDecimal closingBalance;

    private int customerCount;
    private long saleLineCount;

    /** True when this trip corrects another rather than being a fresh entry. */
    private boolean correction;
    private String correctionNote;
}
