package com.app.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.app.utility.MobileNumberRules.Status;

/**
 * The server side of the rule that decides whether a customer can be sent a ledger
 * statement.
 *
 * The cases are the production data, and the same ones are asserted in the
 * browser's src/utils/mobileRules.test.js. The two have to agree: the screen uses
 * its copy for feedback while typing, but this class is what refuses the save, and
 * a number the browser accepted and the server rejected would look like a bug to
 * whoever is doing the data entry.
 */
class MobileNumberRulesTest {

    @Nested
    @DisplayName("classify")
    class Classify {

        @Test
        @DisplayName("accepts the four Indian mobile prefixes")
        void acceptsValidPrefixes() {
            for (String number : new String[] { "6012345678", "7012345678", "8012345678", "9012345678" }) {
                assertEquals(Status.VALID, MobileNumberRules.classify(number), number);
            }
        }

        @Test
        @DisplayName("rejects what is actually in the customer table")
        void rejectsRealBadData() {
            assertEquals(Status.MISSING, MobileNumberRules.classify(null));
            assertEquals(Status.MISSING, MobileNumberRules.classify(""));
            assertEquals(Status.MISSING, MobileNumberRules.classify("   "));
            // "0", "00" and three nine-digit numbers are all in production.
            assertEquals(Status.TOO_SHORT, MobileNumberRules.classify("0"));
            assertEquals(Status.TOO_SHORT, MobileNumberRules.classify("00"));
            assertEquals(Status.TOO_SHORT, MobileNumberRules.classify("909697601"));
            // 1234567890 is on four different customers, 0000000000 on two.
            assertEquals(Status.PLACEHOLDER, MobileNumberRules.classify("1234567890"));
            assertEquals(Status.PLACEHOLDER, MobileNumberRules.classify("0000000000"));
        }

        @Test
        @DisplayName("rejects a prefix no Indian mobile uses")
        void rejectsBadPrefix() {
            assertEquals(Status.BAD_PREFIX, MobileNumberRules.classify("2012345678"));
            assertEquals(Status.BAD_PREFIX, MobileNumberRules.classify("5555555555"));
        }

        @Test
        @DisplayName("rejects too many digits")
        void rejectsTooLong() {
            assertEquals(Status.TOO_LONG, MobileNumberRules.classify("98765432109"));
        }

        @Test
        @DisplayName("uses a placeholder list rather than a repeated-digit rule")
        void placeholdersAreAnExplicitList() {
            assertEquals(Status.PLACEHOLDER, MobileNumberRules.classify("9999999999"));
            // A heuristic on repeated digits would have thrown this away too.
            assertEquals(Status.VALID, MobileNumberRules.classify("9888888888"));
        }
    }

    @Nested
    @DisplayName("normalise")
    class Normalise {

        @Test
        @DisplayName("strips separators, so a number typed with spaces still saves")
        void stripsSeparators() {
            assertEquals("9876543210", MobileNumberRules.normalise("98765 43210"));
            assertEquals("9876543210", MobileNumberRules.normalise("98765-43210"));
            assertEquals("9876543210", MobileNumberRules.normalise("  9876543210  "));
        }

        @Test
        @DisplayName("strips a country code or a trunk zero")
        void stripsCountryCode() {
            assertEquals("9876543210", MobileNumberRules.normalise("+91 98765 43210"));
            assertEquals("9876543210", MobileNumberRules.normalise("919876543210"));
            assertEquals("9876543210", MobileNumberRules.normalise("09876543210"));
        }

        @Test
        @DisplayName("recognises two spellings of one phone as the same number")
        void twoFormsOfOneNumberMatch() {
            // What makes shared-number detection work at all.
            assertEquals(MobileNumberRules.normalise("+919876543210"),
                    MobileNumberRules.normalise("98765 43210"));
        }

        @Test
        @DisplayName("leaves a number it cannot make sense of alone")
        void leavesUnfixableAlone() {
            assertEquals("909697601", MobileNumberRules.normalise("909697601"));
            assertEquals("", MobileNumberRules.normalise(null));
        }
    }

    @Nested
    @DisplayName("format and isValid")
    class Presentation {

        @Test
        @DisplayName("groups a valid number, and shows bad data as recorded")
        void formatting() {
            assertEquals("98765 43211", MobileNumberRules.format("9876543211"));
            // Not dressed up: an operator has to see what is actually stored.
            assertEquals("909697601", MobileNumberRules.format("909697601"));
            assertEquals("00", MobileNumberRules.format("00"));
            assertEquals("", MobileNumberRules.format(null));
        }

        @Test
        @DisplayName("isValid is the one question the send path asks")
        void validity() {
            assertTrue(MobileNumberRules.isValid("9876543211"));
            assertTrue(MobileNumberRules.isValid("+91 98765 43211"));
            assertFalse(MobileNumberRules.isValid("1234567890"));
            assertFalse(MobileNumberRules.isValid(""));
            assertFalse(MobileNumberRules.isValid("909697601"));
        }

        @Test
        @DisplayName("every rejection has a reason worth showing")
        void reasonsAreUseful() {
            for (Status status : Status.values()) {
                String described = MobileNumberRules.describe(status);
                assertFalse(described.isBlank(), status.name());
            }
            assertTrue(MobileNumberRules.describe(Status.TOO_SHORT).contains("10 digits"));
            assertTrue(MobileNumberRules.describe(Status.BAD_PREFIX).contains("6, 7, 8 or 9"));
        }
    }
}
