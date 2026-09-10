package com.app.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    @DisplayName("the WhatsApp summary carries seven values, and the day's detail")
    void dailySaleSummaryCarriesTheDetail() {
        SmsMessageBuilder.Message message = SmsMessageBuilder.dailySaleSummary(
                "Mainuddin Kazi", DATE, 15,
                new BigDecimal("30.000"), new BigDecimal("3000.00"),
                new BigDecimal("500.00"), new BigDecimal("2500.00"));

        assertEquals("Mainuddin Kazi|09-09-2026|15|30.0|3000|500|2500", message.variables());
        // Seven, against the three the SMS template takes - which is why the channel
        // decides which builder is used.
        assertEquals(7, message.variables().split("\\|", -1).length);

        assertTrue(message.body().contains("पक्षी: 15"), message.body());
        assertTrue(message.body().contains("वजन: 30.0"), message.body());
        assertTrue(message.body().contains("₹2,500"), message.body());
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

        assertEquals("|09-09-2026|0|0.0|0|0|0", message.variables());
    }
}
