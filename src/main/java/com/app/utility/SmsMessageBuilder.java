package com.app.utility;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Builds the message a customer receives, in Marathi.
 *
 * Two things come out of every builder: the pipe-separated variable values the
 * Fast2SMS template expects, and the same message written out in full. The written
 * form is stored on the outbox row, because "Javed|09-09-2026|307940" tells nobody
 * in support what the customer actually read.
 *
 * The variable order is fixed by the approved template and cannot change without a
 * new one being approved.
 *
 * The previous version took the date from LocalDate.now() rather than the sale, so a
 * back-dated entry told the customer it happened today.
 */
public final class SmsMessageBuilder {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private SmsMessageBuilder() {
    }

    /** Template values and the readable message, together. */
    public record Message(String variables, String body) {
    }

    /**
     * The message sent today: name, date, balance.
     *
     * Kept to exactly what the existing approved DLT template expects, so sends can
     * start being recorded in the outbox without waiting on a new approval.
     */
    public static Message dailyBalance(String customerName, LocalDate date, BigDecimal balance) {
        String formattedDate = date.format(DATE);

        return new Message(
                // Plain digits in the template value, grouped only in the preview.
                // A DLT message is matched against its approved content, and the
                // code this replaces sent an unformatted number, so "2500" is the
                // form known to be accepted. "2,500" risks a rejection for the sake
                // of a comma the customer sees anyway in the rendered message.
                String.join("|", safe(customerName), formattedDate, plain(balance)),
                String.format(
                        "नमस्कार %s, दिनांक %s रोजी तुमचा विक्री व्यवहार नोंदवला आहे. "
                                + "सध्याची शिल्लक: ₹%s. धन्यवाद!",
                        safe(customerName), formattedDate, money(balance)));
    }

    /**
     * The detailed daily summary: what was supplied, what was paid, what is owed.
     *
     * Needs its own WhatsApp template approved before it can go out - the body here
     * is what that template has to say. Written now so a dry run records the intended
     * message, which is how the wording gets checked before 358 customers read it.
     */
    public static Message dailySaleSummary(String customerName,
                                           LocalDate date,
                                           long birds,
                                           BigDecimal kilograms,
                                           BigDecimal amount,
                                           BigDecimal paid,
                                           BigDecimal balance) {

        String formattedDate = date.format(DATE);

        return new Message(
                String.join("|",
                        safe(customerName),
                        formattedDate,
                        String.valueOf(birds),
                        weight(kilograms),
                        plain(amount),
                        plain(paid),
                        plain(balance)),
                String.format(
                        "नमस्कार %s,%n"
                                + "दिनांक %s%n"
                                + "पक्षी: %d, वजन: %s किलो%n"
                                + "रक्कम: ₹%s, जमा: ₹%s%n"
                                + "एकूण शिल्लक: ₹%s%n"
                                + "धन्यवाद!",
                        safe(customerName), formattedDate, birds,
                        weight(kilograms), money(amount), money(paid), money(balance)));
    }

    /** 307940 - whole rupees, no separators, for a template variable. */
    static String plain(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(0, RoundingMode.HALF_UP).toPlainString();
    }

    /** 3,07,940 - grouped the Indian way, whole rupees, as a customer reads it. */
    static String money(BigDecimal value) {
        BigDecimal rounded = (value == null ? BigDecimal.ZERO : value).setScale(0, RoundingMode.HALF_UP);
        boolean negative = rounded.signum() < 0;
        String digits = rounded.abs().toPlainString();

        StringBuilder grouped = new StringBuilder();
        int length = digits.length();
        // Last three digits, then twos: 1,23,45,678.
        int firstGroup = Math.min(3, length);
        grouped.insert(0, digits.substring(length - firstGroup));
        int index = length - firstGroup;
        while (index > 0) {
            int start = Math.max(0, index - 2);
            grouped.insert(0, digits.substring(start, index) + ",");
            index = start;
        }
        return (negative ? "-" : "") + grouped;
    }

    static String weight(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * A pipe inside a customer name would shift every later value into the wrong
     * template slot, so it is removed rather than escaped.
     */
    private static String safe(String value) {
        return value == null ? "" : value.replace("|", " ").trim();
    }
}
