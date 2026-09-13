package com.app.service.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.DayOfWeek;
import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which week the Monday run reports on.
 *
 * A statement that covered the wrong seven days would be wrong in the way that is hardest
 * to notice: every figure on it is real, the total adds up, and only the dates are off - so
 * it reconciles against nothing and the customer is the one who finds out.
 *
 * The rule is Monday to Sunday of the week that has closed, decided from the run date
 * rather than from "seven days ago", so re-running last Monday's batch produces last
 * Monday's statements and a re-run is safe.
 */
class WeeklyStatementJobTest {

    @Test
    @DisplayName("From a Monday, the week that ended is the Sunday just gone")
    void fromMonday() {
        // Monday 14 September 2026 -> the week that closed on Sunday 13 September.
        LocalDate monday = LocalDate.of(2026, 9, 14);
        assertEquals(DayOfWeek.MONDAY, monday.getDayOfWeek());

        LocalDate weekEnding = WeeklyStatementJob.lastCompletedWeekEnding(monday);

        assertEquals(LocalDate.of(2026, 9, 13), weekEnding);
        assertEquals(DayOfWeek.SUNDAY, weekEnding.getDayOfWeek());
        // And the period it implies is the seven days Monday to Sunday.
        assertEquals(LocalDate.of(2026, 9, 7), weekEnding.minusDays(6));
        assertEquals(DayOfWeek.MONDAY, weekEnding.minusDays(6).getDayOfWeek());
    }

    @Test
    @DisplayName("From mid-week, still the Sunday just gone - not a rolling seven days")
    void fromMidweek() {
        // A manual run on Thursday must produce the same period the Monday run did, or the
        // two would disagree about the same week and one of them would be resent.
        assertEquals(LocalDate.of(2026, 9, 13),
                WeeklyStatementJob.lastCompletedWeekEnding(LocalDate.of(2026, 9, 17)));
        assertEquals(LocalDate.of(2026, 9, 13),
                WeeklyStatementJob.lastCompletedWeekEnding(LocalDate.of(2026, 9, 19)));
    }

    @Test
    @DisplayName("On a Sunday, the completed week is the one before - today is not over")
    void onSunday() {
        LocalDate sunday = LocalDate.of(2026, 9, 13);
        assertEquals(DayOfWeek.SUNDAY, sunday.getDayOfWeek());

        // Reporting on the Sunday you are standing in would send a statement missing that
        // day's trading, and the customer's balance would not match the figure on it.
        assertEquals(LocalDate.of(2026, 9, 6),
                WeeklyStatementJob.lastCompletedWeekEnding(sunday));
    }

    @Test
    @DisplayName("Every day of a week resolves to one period, so a re-run cannot shift it")
    void everyDayInAWeekAgrees() {
        // Monday 14th through Saturday 19th all report on the week ending Sunday 13th.
        // The Sunday is deliberately excluded above, and is the only day that differs.
        for (int offset = 0; offset <= 5; offset++) {
            LocalDate day = LocalDate.of(2026, 9, 14).plusDays(offset);
            assertEquals(LocalDate.of(2026, 9, 13),
                    WeeklyStatementJob.lastCompletedWeekEnding(day),
                    day + " (" + day.getDayOfWeek() + ") should report on the week ending 13 Sep");
        }
    }

    @Test
    @DisplayName("The period is always exactly seven days")
    void alwaysSevenDays() {
        for (int offset = 0; offset < 21; offset++) {
            LocalDate day = LocalDate.of(2026, 9, 1).plusDays(offset);
            LocalDate to = WeeklyStatementJob.lastCompletedWeekEnding(day);
            LocalDate from = to.minusDays(6);

            assertEquals(DayOfWeek.SUNDAY, to.getDayOfWeek(), "week must close on a Sunday");
            assertEquals(DayOfWeek.MONDAY, from.getDayOfWeek(), "week must open on a Monday");
            assertEquals(6, java.time.temporal.ChronoUnit.DAYS.between(from, to));
            // And it must be in the past: a statement cannot cover days that have not happened.
            assertEquals(true, to.isBefore(day) || to.equals(day.minusDays(1)) || to.isBefore(day));
        }
    }
}
