package com.app.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the customer receives, and what the provider is handed.
 *
 * The two differ on purpose. A DLT message is matched against its approved content,
 * so a template variable carries plain digits - which is the form the code this
 * replaces was sending and having accepted - while the stored preview carries the
 * grouped figure a person reads. Getting that backwards means either a rejected
 * message or an audit trail nobody can check.
 */
class SmsMessageBuilderTest {

    private static final LocalDate DATE = LocalDate.parse("2026-09-09");

    @Test
    @DisplayName("the SMS template gets three plain values and the preview gets the readable text")
    void dailyBalanceSplitsTemplateValuesFromDisplay() {
        SmsMessageBuilder.Message message =
                SmsMessageBuilder.dailyBalance("Javed Kureshi", DATE, new BigDecimal("307940.00"));

        // Three values, in the order the approved template expects. No separators.
        assertEquals("Javed Kureshi|09-09-2026|307940", message.variables());

        // The same figure, grouped, in the message the customer sees.
        assertTrue(message.body().contains("₹3,07,940"), message.body());
        assertTrue(message.body().contains("नमस्कार Javed Kureshi"), message.body());
        assertTrue(message.body().contains("09-09-2026"), message.body());
    }

    @Test
    @DisplayName("the date comes from the sale, not from today")
    void usesTheSaleDate() {
        // The previous builder took LocalDate.now(), so a back-dated entry told the
        // customer their sale happened today.
        SmsMessageBuilder.Message message = SmsMessageBuilder.dailyBalance(
                "Javed", LocalDate.parse("2026-08-31"), BigDecimal.TEN);

        assertTrue(message.variables().contains("31-08-2026"), message.variables());
        assertTrue(message.body().contains("31-08-2026"), message.body());
    }

    @Test
    @DisplayName("the WhatsApp summary carries eight values, including the rate")
    void dailySaleSummaryCarriesTheDetail() {
        SmsMessageBuilder.Message message = SmsMessageBuilder.dailySaleSummary(
                "Mainuddin Kazi", DATE, 15,
                new BigDecimal("30.000"), new BigDecimal("3000.00"),
                new BigDecimal("500.00"), new BigDecimal("2500.00"));

        // name | date | birds | weight | rate | amount | paid | balance
        assertEquals("Mainuddin Kazi|09-09-2026|15|30.0|100.00|3000|500|2500", message.variables());
        // Eight, against the three the SMS template takes - which is why the channel
        // decides which builder is used, and why this needs its own template.
        assertEquals(8, message.variables().split("\\|", -1).length);

        assertTrue(message.body().contains("पक्षी: 15"), message.body());
        assertTrue(message.body().contains("वजन: 30.0 किलो"), message.body());
        assertTrue(message.body().contains("दर: ₹100.00 प्रति किलो"), message.body());
        assertTrue(message.body().contains("रक्कम: ₹3,000"), message.body());
        assertTrue(message.body().contains("जमा: ₹500"), message.body());
        assertTrue(message.body().contains("एकूण शिल्लक: ₹2,500"), message.body());
    }

    @Test
    @DisplayName("the rate-free summary drops the rate and renumbers, it does not blank it")
    void dailySaleSummaryNoRateOmitsTheRate() {
        SmsMessageBuilder.Message message = SmsMessageBuilder.dailySaleSummaryNoRate(
                "Mainuddin Kazi", DATE, 15,
                new BigDecimal("30.000"), new BigDecimal("3000.00"),
                new BigDecimal("500.00"), new BigDecimal("2500.00"));

        // name | date | birds | weight | amount | paid | balance - seven, not eight
        // with a zero in the rate slot. Passing zero was the other option considered
        // and it is worse: the approved body reads "दर: ₹{{5}} प्रति किलो", so a zero
        // renders as a rate of nothing, which looks like a billing fault.
        assertEquals("Mainuddin Kazi|09-09-2026|15|30.0|3000|500|2500", message.variables());
        assertEquals(7, message.variables().split("\\|", -1).length);

        assertFalse(message.body().contains("दर"), message.body());
        assertFalse(message.body().contains("प्रति किलो"), message.body());

        // Everything else still reads the same, and in the same order.
        assertTrue(message.body().contains("पक्षी: 15"), message.body());
        assertTrue(message.body().contains("वजन: 30.0 किलो"), message.body());
        assertTrue(message.body().contains("रक्कम: ₹3,000"), message.body());
        assertTrue(message.body().contains("जमा: ₹500"), message.body());
        assertTrue(message.body().contains("एकूण शिल्लक: ₹2,500"), message.body());
    }

    @Test
    @DisplayName("the rate is derived from the day's totals, not from a single line")
    void rateIsTheDaysRealisedRate() {
        // A customer billed at two different rates on one trip has no single line
        // rate; the figure true of the day is the total over the total. 45 kg for
        // 5,000 is 111.11 per kg, and it has to agree with the two numbers printed
        // beside it or the message invites an argument.
        assertEquals(new BigDecimal("111.11"),
                SmsMessageBuilder.ratePerKg(new BigDecimal("5000"), new BigDecimal("45.000")));

        // No weight recorded - 3,691 trips in this data have none - yields zero
        // rather than a division by zero in a customer's message.
        assertEquals(new BigDecimal("0.00"),
                SmsMessageBuilder.ratePerKg(new BigDecimal("5000"), BigDecimal.ZERO));
        assertEquals(new BigDecimal("0.00"),
                SmsMessageBuilder.ratePerKg(new BigDecimal("5000"), null));

        SmsMessageBuilder.Message message = SmsMessageBuilder.dailySaleSummary(
                "Test", DATE, 10, BigDecimal.ZERO, new BigDecimal("1000"),
                BigDecimal.ZERO, new BigDecimal("1000"));
        assertTrue(message.variables().contains("|0.00|"), message.variables());
    }

    @Test
    @DisplayName("a pipe in a customer name is removed, not escaped")
    void stripsPipesFromNames() {
        // A pipe would shift every later value into the wrong template slot, so the
        // customer's balance would arrive in the date field.
        SmsMessageBuilder.Message message =
                SmsMessageBuilder.dailyBalance("Ram | Shyam", DATE, BigDecimal.ONE);

        assertEquals(3, message.variables().split("\\|", -1).length);
        assertEquals("Ram   Shyam|09-09-2026|1", message.variables());
    }

    @Test
    @DisplayName("money is grouped the Indian way")
    void groupsLakhsAndCrores() {
        assertEquals("2,03,67,247", SmsMessageBuilder.money(new BigDecimal("20367247")));
        assertEquals("3,07,940", SmsMessageBuilder.money(new BigDecimal("307940")));
        assertEquals("2,500", SmsMessageBuilder.money(new BigDecimal("2500")));
        assertEquals("500", SmsMessageBuilder.money(new BigDecimal("500")));
        assertEquals("0", SmsMessageBuilder.money(BigDecimal.ZERO));
        assertEquals("0", SmsMessageBuilder.money(null));
        // A customer in credit.
        assertEquals("-4,250", SmsMessageBuilder.money(new BigDecimal("-4250")));
    }

    @Test
    @DisplayName("a null figure is zero rather than a crash in a customer's message")
    void handlesNulls() {
        SmsMessageBuilder.Message message = SmsMessageBuilder.dailySaleSummary(
                null, DATE, 0, null, null, null, null);

        assertEquals("|09-09-2026|0|0.0|0.00|0|0|0", message.variables());
    }
}
