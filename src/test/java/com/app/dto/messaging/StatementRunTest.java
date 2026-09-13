package com.app.dto.messaging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.app.entity.MessageOutbox.Status;

/**
 * The statement screen's status bar.
 *
 * What it has to get right is the difference between "sent" and "arrived". The office uses
 * this line to answer a customer asking whether their statement was sent, and a screen
 * that counted provider acceptance as delivery would have somebody say yes about a
 * document that never landed.
 */
class StatementRunTest {

    private static Object[] row(String weekEnding, Status status, long count) {
        return new Object[] { LocalDate.parse(weekEnding), status, count };
    }

    /*
     * Wrapped, because List.of(oneObjectArray) spreads that one array into three separate
     * elements instead of making a one-row list - the rows then arrive as a LocalDate, a
     * Status and a Long where three arrays were meant.
     */
    private static List<StatementRun> rollUp(Object[]... rows) {
        return StatementRun.rollUp(List.of(rows));
    }

    @Test
    @DisplayName("One line per week, newest first, in the order the query hands them over")
    void groupsByWeek() {
        List<StatementRun> runs = rollUp(
                row("2026-09-06", Status.DELIVERED, 12),
                row("2026-09-06", Status.FAILED, 3),
                row("2026-08-30", Status.DELIVERED, 9));

        assertEquals(2, runs.size());
        assertEquals(LocalDate.of(2026, 9, 6), runs.get(0).weekEnding());
        assertEquals(LocalDate.of(2026, 8, 30), runs.get(1).weekEnding());

        // The week a run covers is Monday to Sunday, so the start is six days back - the
        // date pair printed on the statement itself.
        assertEquals(LocalDate.of(2026, 8, 31), runs.get(0).weekStarting());
        assertEquals(15, runs.get(0).total());
    }

    @Test
    @DisplayName("Statuses are counted separately, and the total is all of them")
    void countsEveryStatus() {
        StatementRun run = rollUp(
                row("2026-09-06", Status.PENDING, 4),
                row("2026-09-06", Status.SENT, 5),
                row("2026-09-06", Status.DELIVERED, 6),
                row("2026-09-06", Status.READ, 7),
                row("2026-09-06", Status.FAILED, 8),
                row("2026-09-06", Status.SKIPPED, 9),
                row("2026-09-06", Status.CANCELLED, 1)).get(0);

        assertEquals(4, run.queued());
        assertEquals(5, run.sent());
        assertEquals(6, run.delivered());
        assertEquals(7, run.read());
        assertEquals(8, run.failed());
        assertEquals(9, run.skipped());
        assertEquals(1, run.cancelled());
        assertEquals(40, run.total());
    }

    @Test
    @DisplayName("Accepted by the provider is not delivered, and does not count as it")
    void acceptedIsNotDelivered() {
        // The state right after a send: everything accepted, nothing yet confirmed. The
        // rate must be unknown rather than either 0% or 100% - one would read as a total
        // failure, the other would claim 50 customers had their balance in hand.
        StatementRun run = rollUp(row("2026-09-06", Status.SENT, 50)).get(0);

        assertEquals(50, run.sent());
        assertEquals(0, run.delivered());
        assertNull(run.deliveryRate(), "no verdict has arrived, so the rate is not known");
    }

    @Test
    @DisplayName("The rate is delivered over judged, and read counts as delivered")
    void deliveryRate() {
        // 18 confirmed (15 delivered + 3 read) against 2 failed = 90%.
        StatementRun run = rollUp(
                row("2026-09-06", Status.DELIVERED, 15),
                row("2026-09-06", Status.READ, 3),
                row("2026-09-06", Status.FAILED, 2),
                // Queued and skipped rows stay outside the rate: neither has been judged,
                // and counting a customer with no phone number as a delivery failure would
                // make the provider look broken when the contact list is what is wrong.
                row("2026-09-06", Status.PENDING, 30),
                row("2026-09-06", Status.SKIPPED, 200)).get(0);

        assertEquals(90.0, run.deliveryRate());
        assertEquals(250, run.total());
    }

    @Test
    @DisplayName("hasFailures is what offers the resend button, and only a failure sets it")
    void hasFailures() {
        assertTrue(rollUp(row("2026-09-06", Status.FAILED, 1)).get(0).hasFailures());

        // A skip is not a failure: no number or no consent is fixed on the customer, and
        // resending would skip again for the same reason.
        assertFalse(rollUp(
                row("2026-09-06", Status.SKIPPED, 254),
                row("2026-09-06", Status.DELIVERED, 2)).get(0).hasFailures());
    }

    @Test
    @DisplayName("A row with no period does not take the screen down")
    void tolerantOfANullPeriod() {
        // reference_date is nullable on the column. A statement should always have one, but
        // a bad row must show up on the screen as a bad row rather than as a 500.
        StatementRun run = rollUp(new Object[] { null, Status.FAILED, 1L }).get(0);

        assertNull(run.weekEnding());
        assertNull(run.weekStarting());
        assertEquals(1, run.failed());
    }

    @Test
    @DisplayName("No runs at all is an empty list, not a fabricated week of zeros")
    void noRuns() {
        assertTrue(rollUp().isEmpty());
    }
}
