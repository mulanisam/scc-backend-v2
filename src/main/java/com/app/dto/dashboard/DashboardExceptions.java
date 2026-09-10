package com.app.dto.dashboard;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * Things that need somebody to look at them.
 *
 * Every condition counted here is real in this database rather than hypothetical:
 * there are sales dated after today, thousands of trips whose bird counts do not
 * tally, and trips whose loaded weight was never recorded at all.
 */
@Data
public class DashboardExceptions {

    /** Sales dated after today: they inflate every forward-looking total. */
    private long futureDatedSales;
    private BigDecimal futureDatedAmount;

    /** Trips where loaded birds != sold + mortality + return to farm. */
    private long tripsWithBirdMismatch;
    private long birdMismatchTotal;

    /**
     * Trips whose own sold count differs from the sum of their sale rows.
     *
     * The header figure is a stored aggregate that nothing recalculates when a sale
     * is edited, so it drifts. Every bird figure on a screen depends on which of
     * the two it was taken from, which is why this is counted rather than papered
     * over by picking one silently.
     */
    private long tripsWhereHeaderDisagreesWithRows;
    private long headerVersusRowsBirdDifference;

    /** Trips with no loaded weight recorded, so shrinkage cannot be measured. */
    private long tripsWithoutLoadedWeight;
    /** Trips where loaded weight exceeds sold weight by more than 2%. */
    private long tripsWithWeightGap;
    private BigDecimal weightGapTotal;

    /** Trips entered as a correction to an earlier one. */
    private long correctionTrips;

    /** Purchases with nothing paid against them. */
    private long unpaidPurchases;
    private BigDecimal unpaidPurchaseAmount;

    private List<String> notes;
}
