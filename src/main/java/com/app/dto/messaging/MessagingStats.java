package com.app.dto.messaging;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.app.entity.MessageOutbox.Channel;
import com.app.entity.MessageOutbox.Status;

/**
 * What the messaging dashboard shows above the table.
 *
 * Per channel, because SMS and WhatsApp are separately configured, separately priced
 * and separately able to break - a single combined "95% delivered" would hide WhatsApp
 * failing completely while SMS carried the day.
 */
public record MessagingStats(
        LocalDate from,
        LocalDate to,
        List<ChannelStats> channels,
        /** Both channels together, so the header has one number. */
        ChannelStats total,
        Reachability reachability,
        Config config) {

    /**
     * @param queued      waiting for the dispatcher
     * @param sent        accepted by the provider, arrival not yet known
     * @param delivered   confirmed on the handset (read counts as delivered)
     * @param read        WhatsApp only, and only with read receipts on
     * @param failed      rejected, or reported undelivered
     * @param skipped     never attempted: no number, or no consent
     * @param deliveryRate delivered ÷ (delivered + failed), or null while nothing is
     *                     yet confirmed either way - showing 0% before the first
     *                     delivery report lands would read as an outage
     */
    public record ChannelStats(
            String channel,
            long queued,
            long sent,
            long delivered,
            long read,
            long failed,
            long skipped,
            long cancelled,
            long total,
            Double deliveryRate) {

        static ChannelStats from(String channel, Map<Status, Long> counts) {
            long queued = counts.getOrDefault(Status.PENDING, 0L);
            long sent = counts.getOrDefault(Status.SENT, 0L);
            long delivered = counts.getOrDefault(Status.DELIVERED, 0L);
            long read = counts.getOrDefault(Status.READ, 0L);
            long failed = counts.getOrDefault(Status.FAILED, 0L);
            long skipped = counts.getOrDefault(Status.SKIPPED, 0L);
            long cancelled = counts.getOrDefault(Status.CANCELLED, 0L);

            long confirmed = delivered + read;
            long judged = confirmed + failed;
            Double rate = judged == 0 ? null : Math.round(confirmed * 1000.0 / judged) / 10.0;

            return new ChannelStats(channel, queued, sent, delivered, read, failed,
                    skipped, cancelled,
                    queued + sent + delivered + read + failed + skipped + cancelled, rate);
        }
    }

    /**
     * Why messages are not going out, which on this account is mostly not a messaging
     * problem: 118 customers have no number at all, 12 have garbage in the field, and
     * nobody has opted in to WhatsApp.
     *
     * On the dashboard because a delivery rate read on its own is misleading - 100% of
     * the messages that were attempted can be delivered while most customers were
     * never sent one, and the number that needs fixing is the second one.
     */
    public record Reachability(
            long activeCustomers,
            /** Valid, unshared, and therefore able to receive a balance. */
            long reachable,
            long unusableNumbers,
            long onSharedNumbers,
            long whatsappOptedIn,
            /** Owed by customers nobody can message. The reason to fix the numbers. */
            BigDecimal unreachableBalance) {
    }

    /** So the screen can say "dry run" rather than silently sending nothing. */
    public record Config(
            boolean enabled,
            boolean providerConfigured,
            String defaultChannel,
            String senderId,
            /**
             * Which template the application actually sends for each purpose, keyed by
             * a label the screen shows. Exposed because the id alone is meaningless -
             * knowing that "Daily sale (WhatsApp)" is 32367 is what tells somebody the
             * rate-free wording is the one going out.
             */
            Map<String, String> templates) {
    }

    /**
     * Folds the repository's (channel, status, count) rows into per-channel blocks,
     * with a zero block for a channel that sent nothing in the period - a missing row
     * and a row of zeros mean the same thing to the reader, and the screen should not
     * have to cope with a channel being absent.
     */
    public static MessagingStats build(LocalDate from, LocalDate to,
                                       List<Object[]> rows,
                                       Reachability reachability,
                                       Config config) {

        Map<Channel, Map<Status, Long>> byChannel = new LinkedHashMap<>();
        Map<Status, Long> combined = new LinkedHashMap<>();
        for (Channel channel : Channel.values()) {
            byChannel.put(channel, new LinkedHashMap<>());
        }

        for (Object[] row : rows) {
            Channel channel = (Channel) row[0];
            Status status = (Status) row[1];
            long count = ((Number) row[2]).longValue();
            byChannel.get(channel).merge(status, count, Long::sum);
            combined.merge(status, count, Long::sum);
        }

        List<ChannelStats> channels = new ArrayList<>();
        byChannel.forEach((channel, counts) -> channels.add(ChannelStats.from(channel.name(), counts)));

        return new MessagingStats(from, to, channels,
                ChannelStats.from("ALL", combined), reachability, config);
    }
}
