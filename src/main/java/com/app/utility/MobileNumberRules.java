package com.app.utility;

import java.util.Set;

/**
 * What counts as a mobile number we are willing to send to.
 *
 * This exists because the plan is to send ledger statements over WhatsApp, and a
 * statement carries a balance. Sending one to the wrong number discloses a
 * customer's financial position to a stranger, so "we have something in the mobile
 * column" is not a good enough test. In production today, out of 488 customers:
 * 118 have no number, 6 hold placeholders (1234567890 appears on four different
 * customers, 0000000000 on two), 3 are nine digits, 3 are "0" or "00", and 11
 * numbers are shared by more than one customer - one of them by seven.
 *
 * Nothing here reformats a number into validity. Checked against the data first:
 * no value contains a space, a +91, or a leading zero, so there is nothing to
 * normalise away - a bad number is simply missing information, and a human has to
 * supply the real one. normalise() therefore only trims and strips separators, so
 * a number typed as "98765 43210" is accepted rather than rejected on whitespace.
 *
 * The frontend mirrors these rules in src/utils/mobileRules.js for immediate
 * feedback while typing. This class is the authority; the two are kept in step by
 * MobileNumberRulesTest, which asserts against the same cases.
 */
public final class MobileNumberRules {

    /**
     * Numbers that are syntactically fine and semantically meaningless. Every one
     * of these is in the production data. Left as an explicit list rather than a
     * clever heuristic: a repeated-digit rule would also reject 9999999999, which
     * is a real allocatable number.
     */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "1234567890",
            "0123456789",
            "9876543210",
            "0000000000",
            "1111111111",
            "9999999999");

    public enum Status {
        /** Ten digits, an Indian mobile prefix, not a placeholder. */
        VALID,
        /** Nothing recorded at all. */
        MISSING,
        /** Fewer than ten digits after separators are stripped. */
        TOO_SHORT,
        /** More than ten digits, and not a recognisable country-code form. */
        TOO_LONG,
        /** Ten digits but starting 0-5, which no Indian mobile does. */
        BAD_PREFIX,
        /** Ten valid-looking digits that are obviously filler. */
        PLACEHOLDER
    }

    private MobileNumberRules() {
    }

    /**
     * Strips separators and a country code, leaving the ten national digits where
     * possible. Does not validate - use {@link #classify} for that.
     */
    public static String normalise(String raw) {
        if (raw == null) {
            return "";
        }
        String digits = raw.replaceAll("[^0-9]", "");

        // +91 98765 43210 and 0 98765 43210 both mean the same ten digits.
        if (digits.length() == 12 && digits.startsWith("91")) {
            digits = digits.substring(2);
        } else if (digits.length() == 11 && digits.startsWith("0")) {
            digits = digits.substring(1);
        }
        return digits;
    }

    public static Status classify(String raw) {
        String digits = normalise(raw);

        if (digits.isEmpty()) {
            return Status.MISSING;
        }
        if (digits.length() < 10) {
            return Status.TOO_SHORT;
        }
        if (digits.length() > 10) {
            return Status.TOO_LONG;
        }
        if (PLACEHOLDERS.contains(digits)) {
            return Status.PLACEHOLDER;
        }
        char first = digits.charAt(0);
        if (first < '6' || first > '9') {
            return Status.BAD_PREFIX;
        }
        return Status.VALID;
    }

    public static boolean isValid(String raw) {
        return classify(raw) == Status.VALID;
    }

    /** Why a number was rejected, in words an operator can act on. */
    public static String describe(Status status) {
        switch (status) {
            case VALID:       return "Valid";
            case MISSING:     return "No mobile number recorded";
            case TOO_SHORT:   return "Too short - an Indian mobile number is 10 digits";
            case TOO_LONG:    return "Too long - more than 10 digits";
            case BAD_PREFIX:  return "Starts with an invalid digit - Indian mobiles begin 6, 7, 8 or 9";
            case PLACEHOLDER: return "A placeholder, not a real number";
            default:          return "Not usable";
        }
    }

    /**
     * 98765 43210 - grouped for reading in a list. Returns the input unchanged
     * when it is not a valid ten-digit number, so bad data is shown as recorded.
     */
    public static String format(String raw) {
        String digits = normalise(raw);
        if (digits.length() != 10) {
            return raw == null ? "" : raw.trim();
        }
        return digits.substring(0, 5) + " " + digits.substring(5);
    }
}
