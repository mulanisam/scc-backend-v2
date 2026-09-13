package com.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.app.dto.messaging.MessagingStats;
import com.app.entity.MessageOutbox;
import com.app.entity.MessageOutbox.Channel;
import com.app.entity.MessageOutbox.Status;

/**
 * Delivery is a separate fact from acceptance, and the dashboard reports it as one.
 *
 * These are the two places that could quietly lie about it: the entity, which decides
 * what a provider report does to a row, and the stats fold, which decides what the
 * figures above the table say. An "undelivered" report that left a row reading SENT, or
 * a delivery rate that showed 0% before any report had arrived, would both tell
 * somebody their customers were informed when they were not - or that messaging had
 * broken when it had not.
 */
class MessageDeliveryStatusTest {

    private MessageOutbox accepted() {
        MessageOutbox message = new MessageOutbox();
        message.setChannel(Channel.WHATSAPP);
        message.setRecipientMobile("7798112855");
        message.markSent("req-1", "{\"return\":true}");
        return message;
    }

    @Test
    @DisplayName("A delivered report moves the row from accepted to delivered")
    void deliveredReport() {
        MessageOutbox message = accepted();
        assertEquals(Status.SENT, message.getStatus());
        assertFalse(message.isTerminal(), "Accepted is not an outcome; it must stay pollable.");

        LocalDateTime when = LocalDateTime.of(2026, 9, 11, 10, 42);
        message.applyDeliveryStatus("delivered", when);

        assertEquals(Status.DELIVERED, message.getStatus());
        assertEquals(when, message.getDeliveredAt());
        assertEquals("delivered", message.getProviderStatus());
        assertNotNull(message.getStatusCheckedAt());
        assertTrue(message.isTerminal(), "Delivered will not change; it should not be polled again.");
    }

    @Test
    @DisplayName("An undelivered report fails the row rather than leaving it looking sent")
    void undeliveredReportFails() {
        MessageOutbox message = accepted();

        message.applyDeliveryStatus("undelivered", LocalDateTime.now());

        assertEquals(Status.FAILED, message.getStatus(),
                "Accepted and then not delivered is a failure, not a success.");
        assertTrue(message.getError().contains("undelivered"));
        assertNull(message.getDeliveredAt());
        assertFalse(message.isTerminal(), "A failure can be resent, so it is not terminal.");
    }

    @Test
    @DisplayName("A read report implies delivery, even if the delivered report was missed")
    void readImpliesDelivered() {
        MessageOutbox message = accepted();

        LocalDateTime when = LocalDateTime.of(2026, 9, 11, 11, 5);
        message.applyDeliveryStatus("read", when);

        assertEquals(Status.READ, message.getStatus());
        assertEquals(when, message.getReadAt());
        assertEquals(when, message.getDeliveredAt(),
                "Polling can catch up after the delivered report has aged out of the log.");
    }

    @Test
    @DisplayName("An unrecognised or in-flight status leaves the row alone")
    void inFlightStatusIsNotAnOutcome() {
        MessageOutbox message = accepted();

        message.applyDeliveryStatus("queued", LocalDateTime.now());

        assertEquals(Status.SENT, message.getStatus(),
                "Queued at the provider is still just accepted.");
        assertEquals("queued", message.getProviderStatus(),
                "The provider's word is kept, so the next poll can interpret it.");
        assertNotNull(message.getStatusCheckedAt());
    }

    @Test
    @DisplayName("A null report records only that the check happened")
    void nullStatusOnlyStamps() {
        MessageOutbox message = accepted();

        message.applyDeliveryStatus(null, null);

        assertEquals(Status.SENT, message.getStatus());
        assertNotNull(message.getStatusCheckedAt(),
                "Otherwise the same message is polled again immediately, every run.");
    }

    // ---- the dashboard's figures ------------------------------------------

    private static Object[] row(Channel channel, Status status, long count) {
        return new Object[] { channel, status, count };
    }

    private static MessagingStats.Reachability noReachability() {
        return new MessagingStats.Reachability(488, 358, 130, 27, 0, new BigDecimal("529260"));
    }

    private static MessagingStats.Config config() {
        return new MessagingStats.Config(true, true, "SMS", "SOHELC",
                Map.of("Daily sale (WhatsApp)", "32367"));
    }

    @Test
    @DisplayName("Counts are folded per channel, and a silent channel still reports zeros")
    void foldsPerChannel() {
        List<Object[]> rows = new ArrayList<>(List.of(
                row(Channel.SMS, Status.DELIVERED, 40L),
                row(Channel.SMS, Status.FAILED, 10L),
                row(Channel.SMS, Status.PENDING, 5L)));

        MessagingStats stats = MessagingStats.build(
                LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 11),
                rows, noReachability(), config());

        MessagingStats.ChannelStats sms = stats.channels().stream()
                .filter(block -> "SMS".equals(block.channel())).findFirst().orElseThrow();
        assertEquals(40, sms.delivered());
        assertEquals(10, sms.failed());
        assertEquals(55, sms.total());

        MessagingStats.ChannelStats whatsapp = stats.channels().stream()
                .filter(block -> "WHATSAPP".equals(block.channel())).findFirst().orElseThrow();
        assertEquals(0, whatsapp.total(),
                "A channel that sent nothing must still appear; absent and zero mean the "
                        + "same thing to the reader.");
        assertNull(whatsapp.deliveryRate(),
                "Nothing was attempted, so there is no rate - not a rate of zero.");
    }

    @Test
    @DisplayName("The delivery rate counts read as delivered and ignores what is still in flight")
    void deliveryRateIgnoresInFlight() {
        List<Object[]> rows = new ArrayList<>(List.of(
                row(Channel.WHATSAPP, Status.DELIVERED, 7L),
                row(Channel.WHATSAPP, Status.READ, 3L),
                row(Channel.WHATSAPP, Status.FAILED, 10L),
                // Neither confirmed nor failed yet: these must not drag the rate down.
                row(Channel.WHATSAPP, Status.SENT, 100L),
                row(Channel.WHATSAPP, Status.SKIPPED, 300L)));

        MessagingStats stats = MessagingStats.build(
                LocalDate.now().minusDays(7), LocalDate.now(), rows, noReachability(), config());

        MessagingStats.ChannelStats whatsapp = stats.channels().stream()
                .filter(block -> "WHATSAPP".equals(block.channel())).findFirst().orElseThrow();

        assertEquals(50.0, whatsapp.deliveryRate(),
                "10 confirmed against 10 failed. The 100 awaiting a report and the 300 "
                        + "never attempted are not verdicts.");
        assertEquals(420, whatsapp.total());
    }

    @Test
    @DisplayName("The total block adds both channels together")
    void totalsBothChannels() {
        List<Object[]> rows = new ArrayList<>(List.of(
                row(Channel.SMS, Status.DELIVERED, 20L),
                row(Channel.WHATSAPP, Status.DELIVERED, 5L),
                row(Channel.WHATSAPP, Status.FAILED, 5L)));

        MessagingStats stats = MessagingStats.build(
                LocalDate.now().minusDays(1), LocalDate.now(), rows, noReachability(), config());

        assertEquals(25, stats.total().delivered());
        assertEquals(5, stats.total().failed());
        assertEquals(30, stats.total().total());
    }
}
