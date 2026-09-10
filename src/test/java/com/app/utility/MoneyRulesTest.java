package com.app.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.app.utility.MoneyRules.BirdReconciliation;

/**
 * Mirrors the frontend businessRules test suite so the two sides of the rule
 * cannot drift apart. Cases are deliberately identical, including the
 * floating-point half-step cases that were wrong in the browser.
 */
class MoneyRulesTest {

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, bd(expected).compareTo(actual),
                () -> "expected " + expected + " but got " + actual);
    }

    @Nested
    @DisplayName("roundToNearest")
    class RoundToNearest {

        @Test
        void roundsToWholeRupeesHalvesUp() {
            assertAmount("4", MoneyRules.roundToNearest(bd("4.4"), 1));
            assertAmount("5", MoneyRules.roundToNearest(bd("4.5"), 1));
            assertAmount("5", MoneyRules.roundToNearest(bd("4.6"), 1));
        }

        @Test
        void roundsToAnArbitraryIncrement() {
            assertAmount("250", MoneyRules.roundToNearest(bd("254"), 10));
            assertAmount("260", MoneyRules.roundToNearest(bd("255"), 10));
            assertAmount("260", MoneyRules.roundToNearest(bd("256"), 10));
        }

        @Test
        void treatsNullAsZero() {
            assertAmount("0", MoneyRules.roundToNearest(null, 10));
        }

        @Test
        void rejectsNonPositiveIncrement() {
            assertThrows(IllegalArgumentException.class,
                    () -> MoneyRules.roundToNearest(bd("100"), 0));
            assertThrows(IllegalArgumentException.class,
                    () -> MoneyRules.roundToNearest(bd("100"), -10));
        }
    }

    @Nested
    @DisplayName("calculateAmount")
    class CalculateAmount {

        @Test
        void roundsSaleAmountsToNearestTenRupees() {
            assertEquals(10, MoneyRules.AMOUNT_ROUNDING_INCREMENT);
            assertAmount("250", MoneyRules.calculateAmount(bd("2.5"), bd("100")));
            assertAmount("250", MoneyRules.calculateAmount(bd("2.53"), bd("100")));
            assertAmount("260", MoneyRules.calculateAmount(bd("2.56"), bd("100")));
            assertAmount("100", MoneyRules.calculateAmount(bd("1.2"), bd("87")));
        }

        /**
         * These land exactly on a half-step. In binary floating point
         * 2.55 * 100 is 254.99999999999997, which rounds the wrong way;
         * BigDecimal makes it exactly 255.
         */
        @Test
        void roundsUpOnHalfStepsWithNoFloatingPointError() {
            assertAmount("260", MoneyRules.calculateAmount(bd("2.55"), bd("100")));
            assertAmount("120", MoneyRules.calculateAmount(bd("1.15"), bd("100")));
            assertAmount("40", MoneyRules.calculateAmount(bd("0.35"), bd("100")));
            assertAmount("350", MoneyRules.calculateAmount(bd("3.45"), bd("100")));
            assertAmount("820", MoneyRules.calculateAmount(bd("8.15"), bd("100")));
            assertAmount("1010", MoneyRules.calculateAmount(bd("1.005"), bd("1000")));
        }

        @Test
        void treatsMissingInputsAsZero() {
            assertAmount("0", MoneyRules.calculateAmount(null, bd("100")));
            assertAmount("0", MoneyRules.calculateAmount(bd("2.5"), null));
            assertAmount("0", MoneyRules.calculateAmount(null, null));
        }

        @Test
        void handlesRealisticLoadSizes() {
            assertAmount("262960", MoneyRules.calculateAmount(bd("1234.567"), bd("213")));
        }
    }

    @Nested
    @DisplayName("calculatePending")
    class CalculatePending {

        /**
         * Pending is the exact difference. It must not take the 10-rupee
         * rounding, or it will drift from the stored ledger balance.
         */
        @Test
        void isTheExactDifference() {
            assertAmount("113", MoneyRules.calculatePending(bd("250"), bd("137")));
            assertAmount("5", MoneyRules.calculatePending(bd("250"), bd("245")));
        }

        @Test
        void treatsAClearedPaymentAsZero() {
            assertAmount("250", MoneyRules.calculatePending(bd("250"), null));
        }

        @Test
        void goesNegativeWhenTheCustomerOverpays() {
            assertAmount("-50", MoneyRules.calculatePending(bd("250"), bd("300")));
        }
    }

    @Nested
    @DisplayName("calculateTotalExpenses")
    class TotalExpenses {

        @Test
        void sumsDriverExpenseDieselAndHamali() {
            assertAmount("2000",
                    MoneyRules.calculateTotalExpenses(bd("500"), bd("1200"), bd("300")));
        }

        @Test
        void toleratesMissingFields() {
            assertAmount("100", MoneyRules.calculateTotalExpenses(null, bd("100"), null));
        }
    }

    @Nested
    @DisplayName("amountsMatch")
    class AmountsMatch {

        @Test
        void comparesByValueNotScale() {
            assertTrue(MoneyRules.amountsMatch(bd("250"), bd("250.00")));
            assertTrue(MoneyRules.amountsMatch(null, bd("0")));
            assertFalse(MoneyRules.amountsMatch(bd("250"), bd("260")));
        }
    }

    @Nested
    @DisplayName("reconcileBirds")
    class ReconcileBirds {

        @Test
        void balancesWhenLoadedEqualsSoldPlusMortalityPlusReturned() {
            BirdReconciliation result = MoneyRules.reconcileBirds(100, 90, 5, 5);
            assertTrue(result.isBalanced());
            assertEquals(0, result.getDifference());
            assertNull(result.getMessage());
        }

        @Test
        void reportsBirdsUnaccountedFor() {
            BirdReconciliation result = MoneyRules.reconcileBirds(100, 90, 4, 3);
            assertFalse(result.isBalanced());
            assertEquals(3, result.getDifference());
            assertEquals(97, result.getAccountedFor());
            assertEquals("3 birds unaccounted for", result.getMessage());
        }

        @Test
        void reportsMoreBirdsDistributedThanLoaded() {
            BirdReconciliation result = MoneyRules.reconcileBirds(100, 95, 4, 3);
            assertEquals(-2, result.getDifference());
            assertEquals("2 birds more than loaded", result.getMessage());
        }

        @Test
        void singularisesAOneBirdDiscrepancy() {
            assertEquals("1 bird unaccounted for",
                    MoneyRules.reconcileBirds(100, 99, 0, 0).getMessage());
            assertEquals("1 bird more than loaded",
                    MoneyRules.reconcileBirds(100, 101, 0, 0).getMessage());
        }

        @Test
        void treatsNullCountsAsZero() {
            BirdReconciliation result = MoneyRules.reconcileBirds(100, null, null, null);
            assertEquals(0, result.getAccountedFor());
            assertEquals(100, result.getDifference());
        }
    }
}
