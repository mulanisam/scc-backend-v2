package com.app.utility;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * How figures read on a statement.
 *
 * A deliberate port of frontend/src/components/ledger/ledgerStatement.js, so the PDF
 * the server generates and the table the browser shows state the same numbers the same
 * way. The screen still uses the JavaScript; both are covered by tests that assert the
 * same expected strings, which is what keeps them from drifting.
 */
public final class StatementFormat {

    private StatementFormat() {
    }

    /**
     * Indian digit grouping: 12,34,567.00.
     *
     * Written out rather than taken from a NumberFormat for the en-IN locale, because
     * that locale is not guaranteed to be present in every JVM this runs on and the
     * grouping silently falls back to 1,234,567 when it is missing - which would print
     * a number no customer here reads without effort.
     */
    public static String money(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        BigDecimal rounded = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        String plain = rounded.abs().toPlainString();

        int dot = plain.indexOf('.');
        String whole = dot < 0 ? plain : plain.substring(0, dot);
        String fraction = dot < 0 ? "00" : plain.substring(dot + 1);

        return (negative ? "-" : "") + group(whole) + "." + fraction;
    }

    /**
     * The last three digits, then pairs: 1,23,45,678.
     */
    static String group(String digits) {
        if (digits.length() <= 3) {
            return digits;
        }
        String last3 = digits.substring(digits.length() - 3);
        String rest = digits.substring(0, digits.length() - 3);

        // Pairs off the right-hand end of what is left. The loop stops at one or two
        // digits, never zero, so the remainder always prefixes without a stray comma.
        StringBuilder grouped = new StringBuilder();
        int index = rest.length();
        while (index > 2) {
            grouped.insert(0, "," + rest.substring(index - 2, index));
            index -= 2;
        }
        grouped.insert(0, rest.substring(0, index));

        return grouped + "," + last3;
    }

    /**
     * A balance with the side it falls on: "1,23,456.00 Dr".
     *
     * Dr and Cr rather than a minus sign, because that is how a customer reads a
     * statement from anyone else and a bare negative invites the question of whose
     * favour it is in. Zero gets trailing spaces so the column still aligns.
     */
    public static String balance(BigDecimal value) {
        BigDecimal amount = value == null ? BigDecimal.ZERO : value;
        if (amount.signum() == 0) {
            return money(BigDecimal.ZERO) + "    ";
        }
        return money(amount.abs()) + (amount.signum() > 0 ? " Dr" : " Cr");
    }

    /** Three decimals, blank at zero - an empty cell reads better than 0.000. */
    public static String weight(BigDecimal value) {
        if (value == null || value.signum() == 0) {
            return "";
        }
        BigDecimal rounded = value.setScale(3, RoundingMode.HALF_UP);
        String plain = rounded.abs().toPlainString();
        int dot = plain.indexOf('.');
        return (rounded.signum() < 0 ? "-" : "")
                + group(plain.substring(0, dot)) + "." + plain.substring(dot + 1);
    }

    public static String rate(BigDecimal value) {
        return value == null || value.signum() == 0 ? "" : money(value);
    }

    public static String count(long value) {
        if (value == 0) {
            return "";
        }
        return (value < 0 ? "-" : "") + group(String.valueOf(Math.abs(value)));
    }

    public static String count(BigDecimal value) {
        return value == null ? "" : count(value.longValue());
    }

    private static final String[] MONTHS = {
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    /**
     * 09 Sep 2026 - fixed width, so a column of dates lines up.
     *
     * The months are a literal table rather than a DateTimeFormatter pattern for the
     * same reason the browser has one: the en-IN short form gives "Sept" for September,
     * eleven characters for every month and twelve for one, which is exactly the
     * misalignment this column exists to avoid.
     */
    public static String date(LocalDate value) {
        if (value == null) {
            return "";
        }
        return String.format("%02d %s %d",
                value.getDayOfMonth(), MONTHS[value.getMonthValue() - 1], value.getYear());
    }

    // ---- amount in words --------------------------------------------------

    private static final String[] ONES = {
        "", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten",
        "Eleven", "Twelve", "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen",
        "Eighteen", "Nineteen"
    };

    private static final String[] TENS = {
        "", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"
    };

    private static String twoDigits(int value) {
        if (value < 20) {
            return ONES[value];
        }
        return TENS[value / 10] + (value % 10 != 0 ? " " + ONES[value % 10] : "");
    }

    /**
     * The closing balance in words, grouped the Indian way.
     *
     * A statement states the amount in words as well as figures; it is what makes a
     * disputed figure settleable, because a digit can be misread and "Two Lakh Seven
     * Thousand Nine Hundred Forty" cannot.
     *
     * The sign is dropped deliberately - the band above it says whether the amount is
     * payable or held in advance, so the words do not need to carry it too.
     */
    public static String amountInWords(BigDecimal value) {
        BigDecimal amount = (value == null ? BigDecimal.ZERO : value)
                .abs().setScale(2, RoundingMode.HALF_UP);

        long rupees = amount.longValue();
        int paise = amount.subtract(BigDecimal.valueOf(rupees))
                .movePointRight(2).setScale(0, RoundingMode.HALF_UP).intValue();

        if (rupees == 0 && paise == 0) {
            return "Rupees Nil";
        }
        // Paise with no rupees behind them read as "Fifty Paise only". Saying
        // "Rupees and Fifty Paise only" - which is what the browser version did - puts
        // a dangling "Rupees" on the one line of the statement a dispute is settled
        // against. Rare, since balances are rounded to ten rupees, but wrong.
        if (rupees == 0) {
            return twoDigits(paise) + " Paise only";
        }

        long[] sizes = { 10000000L, 100000L, 1000L, 100L };
        String[] names = { "Crore", "Lakh", "Thousand", "Hundred" };

        StringBuilder parts = new StringBuilder();
        long remaining = rupees;
        for (int i = 0; i < sizes.length; i++) {
            long groupCount = remaining / sizes[i];
            if (groupCount > 0) {
                append(parts, twoDigits((int) groupCount) + " " + names[i]);
                remaining -= groupCount * sizes[i];
            }
        }
        if (remaining > 0) {
            append(parts, twoDigits((int) remaining));
        }

        StringBuilder words = new StringBuilder("Rupees " + parts.toString().trim());
        if (paise > 0) {
            words.append(" and ").append(twoDigits(paise)).append(" Paise");
        }
        return words + " only";
    }

    private static void append(StringBuilder target, String part) {
        if (target.length() > 0) {
            target.append(' ');
        }
        target.append(part);
    }
}
