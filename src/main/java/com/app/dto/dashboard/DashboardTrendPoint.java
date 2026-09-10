package com.app.dto.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/**
 * One day of trading.
 *
 * Days with no trading are present and zeroed rather than skipped, so a gap in the
 * day-by-day table reads as a gap instead of the table quietly closing up.
 *
 * Bird figures come from two places and must not be confused: birdsSold is summed
 * from the sale rows, while birdsLoaded, mortality and returnToFarm exist only on
 * the trip record. birdTally is the invariant that ties them together.
 */
@Data
public class DashboardTrendPoint {

    private LocalDate date;
    private String label;
    private String weekday;
    private long tripCount;

    private long birdsSold;
    private BigDecimal weight;
    private BigDecimal amount;
    private BigDecimal received;
    private BigDecimal pending;

    /** From the trip record: birds put on the vehicle. */
    private long birdsLoaded;
    private long mortality;
    private long returnToFarm;

    /**
     * birdsLoaded - birdsSold - mortality - returnToFarm.
     *
     * Zero means the day tallies. Anything else is birds the records cannot
     * account for, which is a counting or entry error rather than a rounding
     * artefact - birds are whole numbers.
     *
     * birdsSold here is the sum of the sale rows, which is what was billed, so the
     * four figures on a row visibly add up. Where the trip header's own sold count
     * disagrees with its sale rows, tripRecordAgrees says so instead.
     */
    private long birdTally;
    private boolean tallies;

    /** The trip header's own sold count, kept for comparison against the rows. */
    private long birdsSoldOnTripRecord;
    /** False when the trip header's sold count differs from the sum of its sales. */
    private boolean tripRecordAgrees;

    private boolean traded;
}
