package com.app.utility;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Authoritative money and quantity rules for sales, purchases and trading.
 *
 * These rules previously existed only in the React frontend, and the server
 * stored whatever amount the browser sent. That let two screens disagree - the
 * driver screen rounded to the nearest 10 rupees while the bulk screen rounded
 * to the nearest rupee - and both were accepted. The rule now lives here, on
 * the server, and the client value is treated as a checksum.
 *
 * Everything is BigDecimal. Binary floating point cannot represent decimal
 * currency exactly, so a double-based running balance drifts in a way no report
 * can reconcile.
 */
public final class MoneyRules {

    /** Sale amounts are rounded to the nearest 10 rupees for cash handling. */
    public static final int AMOUNT_ROUNDING_INCREMENT = 10;

    /** Currency is stored to paise. */
    public static final int MONEY_SCALE = 2;

    /** Weights are stored to grams. */
    public static final int WEIGHT_SCALE = 3;

    private static final BigDecimal INCREMENT = BigDecimal.valueOf(AMOUNT_ROUNDING_INCREMENT);

    private MoneyRules() {
    }

    /** Null-safe conversion to a scaled amount, treating absent values as zero. */
    public static BigDecimal money(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** Null-safe conversion to a scaled weight, treating absent values as zero. */
    public static BigDecimal weight(BigDecimal value) {
        return value == null
                ? BigDecimal.ZERO.setScale(WEIGHT_SCALE)
                : value.setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Round to the nearest multiple of {@code increment}, halves rounding up.
     *
     * @param value     the amount to round; null is treated as zero
     * @param increment must be positive
     */
    public static BigDecimal roundToNearest(BigDecimal value, int increment) {
        if (increment <= 0) {
            throw new IllegalArgumentException("increment must be positive, got " + increment);
        }
        if (value == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE);
        }

        BigDecimal step = BigDecimal.valueOf(increment);
        return value
                .divide(step, 0, RoundingMode.HALF_UP)
                .multiply(step)
                .setScale(MONEY_SCALE);
    }

    /**
     * Line amount: kilograms x rate, rounded to the nearest 10 rupees.
     *
     * BigDecimal removes the floating-point hazard here: 2.55 x 100 is exactly
     * 255, which rounds up to 260. The same expression in binary floating point
     * evaluates to 254.99999999999997 and rounds down to 250.
     */
    public static BigDecimal calculateAmount(BigDecimal kilograms, BigDecimal rate) {
        if (kilograms == null || rate == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE);
        }
        return roundToNearest(kilograms.multiply(rate), AMOUNT_ROUNDING_INCREMENT);
    }

    /**
     * Outstanding balance on a line: the exact difference, deliberately not
     * rounded to the nearest 10. Payment is whatever cash was actually handed
     * over, and rounding here would drift from the stored ledger.
     */
    public static BigDecimal calculatePending(BigDecimal amount, BigDecimal payment) {
        return money(money(amount).subtract(money(payment)));
    }

    /** Total trip expenses: driver expense + diesel + hamali. */
    public static BigDecimal calculateTotalExpenses(BigDecimal driverExpense, BigDecimal diesel,
            BigDecimal hamali) {
        return money(money(driverExpense).add(money(diesel)).add(money(hamali)));
    }

    /** Whether two amounts agree once both are scaled to paise. */
    public static boolean amountsMatch(BigDecimal a, BigDecimal b) {
        return money(a).compareTo(money(b)) == 0;
    }

    /**
     * Every bird loaded onto the vehicle must be accounted for:
     * {@code totalBirds = sold + mortality + returnToFarm}.
     */
    public static BirdReconciliation reconcileBirds(Integer totalBirds, Integer soldBirds,
            Integer mortality, Integer returnToFarm) {
        int loaded = totalBirds == null ? 0 : totalBirds;
        int accountedFor = zero(soldBirds) + zero(mortality) + zero(returnToFarm);
        return new BirdReconciliation(loaded, accountedFor);
    }

    private static int zero(Integer value) {
        return value == null ? 0 : value;
    }

    /**
     * Result of a bird count check. {@code difference} is positive when birds
     * are unaccounted for and negative when more were distributed than loaded.
     */
    public static final class BirdReconciliation {

        private final int totalBirds;
        private final int accountedFor;

        BirdReconciliation(int totalBirds, int accountedFor) {
            this.totalBirds = totalBirds;
            this.accountedFor = accountedFor;
        }

        public int getTotalBirds() {
            return totalBirds;
        }

        public int getAccountedFor() {
            return accountedFor;
        }

        public int getDifference() {
            return totalBirds - accountedFor;
        }

        public boolean isBalanced() {
            return getDifference() == 0;
        }

        /** Human-readable discrepancy, or null when the counts balance. */
        public String getMessage() {
            int difference = getDifference();
            if (difference == 0) {
                return null;
            }
            int magnitude = Math.abs(difference);
            String birds = magnitude == 1 ? "bird" : "birds";
            return difference > 0
                    ? magnitude + " " + birds + " unaccounted for"
                    : magnitude + " " + birds + " more than loaded";
        }
    }
}
