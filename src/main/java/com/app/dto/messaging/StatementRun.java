package com.app.dto.messaging;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.app.entity.MessageOutbox.Status;

/**
 * One week's statement run, as the messaging screen's status bar shows it.
 *
 * A run is identified by the week it covers, not by when it was sent. That distinction is
 * the whole point of the grouping: a Monday batch that half-failed and was resent on
 * Thursday is still one run - last week's - and somebody asking "did last week's
 * statements go out?" wants one line with one answer, not two lines split by send date.
 *
 * Sent and delivered are kept apart here for the same reason they are on the outbox.
 * Fast2SMS accepting a statement is not the customer receiving it, and a screen that
 * merged the two would have the office assuring a customer they were sent a balance that
 * never arrived.
 */
public record StatementRun(
        /** The Sunday the week closed on. Its identity. */
        LocalDate weekEnding,
        LocalDate weekStarting,
        long queued,
        /** Accepted by the provider; arrival not yet confirmed. */
        long sent,
        long delivered,
        long read,
        long failed,
        /** Never attempted: no number, or no consent. */
        long skipped,
        long cancelled,
        long total,
        /** delivered ÷ judged, or null while nothing has a verdict either way. */
        Double deliveryRate,
        /** True when at least one row can be sent again. Drives the resend button. */
        boolean hasFailures) {

    /**
     * Folds the repository's (period, status, count) rows into one entry per week,
     * newest first.
     *
     * A week with no rows at all does not appear, and that is correct rather than a gap:
     * unlike a channel, which always exists and may simply have been quiet, a week with no
     * statement rows is a week the run never happened for - and inventing a row of zeros
     * for it would claim a run that produced nothing, which is a different fact from no
     * run at all.
     */
    public static List<StatementRun> rollUp(List<Object[]> rows) {
        // Insertion-ordered, and the query hands them over newest first, so the order the
        // screen wants survives without a second sort.
        Map<LocalDate, Map<Status, Long>> byWeek = new LinkedHashMap<>();

        for (Object[] row : rows) {
            LocalDate weekEnding = (LocalDate) row[0];
            Status status = (Status) row[1];
            long count = ((Number) row[2]).longValue();
            byWeek.computeIfAbsent(weekEnding, key -> new LinkedHashMap<>())
                    .merge(status, count, Long::sum);
        }

        List<StatementRun> runs = new ArrayList<>();
        byWeek.forEach((weekEnding, counts) -> runs.add(from(weekEnding, counts)));
        return runs;
    }

    static StatementRun from(LocalDate weekEnding, Map<Status, Long> counts) {
        long queued = counts.getOrDefault(Status.PENDING, 0L);
        long sent = counts.getOrDefault(Status.SENT, 0L);
        long delivered = counts.getOrDefault(Status.DELIVERED, 0L);
        long read = counts.getOrDefault(Status.READ, 0L);
        long failed = counts.getOrDefault(Status.FAILED, 0L);
        long skipped = counts.getOrDefault(Status.SKIPPED, 0L);
        long cancelled = counts.getOrDefault(Status.CANCELLED, 0L);

        long confirmed = delivered + read;
        long judged = confirmed + failed;
        // One decimal, and null rather than 0% before the first report: a run that has
        // just been accepted by the provider has no verdict yet, and 0% would read as a
        // total failure.
        Double rate = judged == 0 ? null : Math.round(confirmed * 1000.0 / judged) / 10.0;

        return new StatementRun(
                weekEnding,
                // Null-tolerant: reference_date is nullable on the column, and a row
                // without one should not take the whole screen down with an NPE.
                weekEnding == null ? null : weekEnding.minusDays(6),
                queued, sent, delivered, read, failed, skipped, cancelled,
                queued + sent + delivered + read + failed + skipped + cancelled,
                rate,
                failed > 0);
    }
}
