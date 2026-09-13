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
     * The daily WhatsApp message: the whole transaction, in the order a trader
     * checks it.
     *
     * Eight variables, each a figure the customer would otherwise ring up to ask
     * about:
     *
     *   {{1}} name        {{5}} rate per kg
     *   {{2}} date        {{6}} amount billed
     *   {{3}} birds       {{7}} paid today
     *   {{4}} weight      {{8}} total balance
     *
     * The rate is the addition that matters. It is the number a poultry trader
     * checks first - it moves daily and it is what an argument is usually about -
     * and it was missing from the earlier draft, which listed weight and amount and
     * left the customer to divide one by the other. It is derived here rather than
     * passed in, so it always equals amount over weight as billed and cannot
     * disagree with the two figures printed beside it.
     *
     * Paid and balance are both present because they answer different questions:
     * what was settled today, and what is still owed altogether. A customer seeing
     * only the balance cannot tell whether today's payment was recorded.
     *
     * This needs its own approved WhatsApp template - the existing one takes three
     * variables. docs/whatsapp-templates.md holds the text to submit.
     */
    public static Message dailySaleSummary(String customerName,
                                           LocalDate date,
                                           long birds,
                                           BigDecimal kilograms,
                                           BigDecimal amount,
                                           BigDecimal paid,
                                           BigDecimal balance) {

        String formattedDate = date.format(DATE);
        BigDecimal ratePerKg = ratePerKg(amount, kilograms);

        return new Message(
                String.join("|",
                        safe(customerName),
                        formattedDate,
                        String.valueOf(birds),
                        weight(kilograms),
                        rate(ratePerKg),
                        plain(amount),
                        plain(paid),
                        plain(balance)),
                String.format(
                        "नमस्कार %s,%n"
                                + "%n"
                                + "दिनांक %s चा व्यवहार:%n"
                                + "पक्षी: %d%n"
                                + "वजन: %s किलो%n"
                                + "दर: ₹%s प्रति किलो%n"
                                + "रक्कम: ₹%s%n"
                                + "जमा: ₹%s%n"
                                + "%n"
                                + "एकूण शिल्लक: ₹%s%n"
                                + "%n"
                                + "धन्यवाद!",
                        safe(customerName), formattedDate, birds,
                        weight(kilograms), rate(ratePerKg),
                        money(amount), money(paid), money(balance)));
    }

    /**
     * The same daily message with the rate line removed. Seven variables:
     *
     *   {{1}} name        {{5}} amount billed
     *   {{2}} date        {{6}} paid today
     *   {{3}} birds       {{7}} total balance
     *   {{4}} weight
     *
     * The owner does not want the per-kilo rate on a customer's phone. Worth being
     * clear about what this does and does not achieve: amount and weight are both
     * still in the message, so anyone who divides one by the other has the rate back.
     * It keeps the figure off a screen somebody might hold up in a market; it is not
     * confidentiality.
     *
     * A separate method rather than a flag, because the two feed different approved
     * templates and the variable count is what the provider validates. A boolean that
     * dropped one value would silently shift every later variable into the wrong slot -
     * the balance would print as the amount.
     */
    public static Message dailySaleSummaryNoRate(String customerName,
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
                                + "%n"
                                + "दिनांक %s चा व्यवहार:%n"
                                + "पक्षी: %d%n"
                                + "वजन: %s किलो%n"
                                + "रक्कम: ₹%s%n"
                                + "जमा: ₹%s%n"
                                + "%n"
                                + "एकूण शिल्लक: ₹%s%n"
                                + "%n"
                                + "धन्यवाद!",
                        safe(customerName), formattedDate, birds,
                        weight(kilograms),
                        money(amount), money(paid), money(balance)));
    }

    /**
     * A receipt for money taken, for the WhatsApp pay_received template.
     *
     * Three variables - name, date, amount - matching the approved body "आज रोजी {{2}}
     * आपल्याकडुन {{3}} रुपये जमा झाले". It states what arrived, not what is left,
     * because that is what the customer is acknowledging; the balance goes out on the
     * daily message and on the statement.
     *
     * The SMS side uses {@link #dailyBalance} instead: the approved DLT template there
     * reads "सध्याची शिल्लक", a balance, so sending an amount received in that slot
     * would put the wrong number behind the wrong words.
     */
    public static Message paymentReceived(String customerName, LocalDate date, BigDecimal amount) {
        String formattedDate = date.format(DATE);

        return new Message(
                String.join("|", safe(customerName), formattedDate, plain(amount)),
                String.format(
                        "नमस्कार %s, दिनांक %s रोजी तुमच्याकडून ₹%s जमा झाले. धन्यवाद!",
                        safe(customerName), formattedDate, money(amount)));
    }

    /**
     * Realised rate: amount billed over weight sold.
     *
     * Derived rather than taken from the sale rows, because a customer with two
     * lines on one trip may have been billed at two different rates, and the one
     * figure that is true of the day as a whole is the total over the total. Zero
     * weight yields zero instead of an error - a line can be recorded with no
     * weight, and 3,691 trips in this data have no loaded weight at all.
     */
    static BigDecimal ratePerKg(BigDecimal amount, BigDecimal kilograms) {
        if (kilograms == null || kilograms.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return (amount == null ? BigDecimal.ZERO : amount)
                .divide(kilograms, 2, RoundingMode.HALF_UP);
    }

    /** 152.25 - two decimals, no separators, for a rate. */
    static String rate(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP).toPlainString();
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
