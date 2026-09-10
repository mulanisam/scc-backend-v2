package com.app.dto.report;

import java.time.LocalDate;
import java.time.format.TextStyle;
import java.util.Locale;

/**
 * How a report groups rows along time.
 *
 * Each constant carries the SQL expression that reduces a date to the first day
 * of its bucket. These fragments are interpolated into report queries, which is
 * safe precisely because they come from this fixed set and never from a request
 * parameter.
 */
public enum ReportPeriod {

    /** One row per calendar day. */
    DAY("%s"),

    /** One row per week, keyed to the Monday of that week. */
    WEEK("DATE_SUB(%1$s, INTERVAL WEEKDAY(%1$s) DAY)"),

    /** One row per calendar month, keyed to the 1st. */
    MONTH("DATE_FORMAT(%s, '%%Y-%%m-01')"),

    /** One row per calendar year, keyed to 1 January. */
    YEAR("DATE_FORMAT(%s, '%%Y-01-01')"),

    /** A single row covering the whole range. */
    ALL(null);

    private final String bucketTemplate;

    ReportPeriod(String bucketTemplate) {
        this.bucketTemplate = bucketTemplate;
    }

    /**
     * SQL that reduces {@code dateColumn} to the start of its bucket.
     * For {@link #ALL} every row collapses onto a single constant bucket.
     */
    public String bucketExpression(String dateColumn) {
        if (this == ALL) {
            return "DATE('1900-01-01')";
        }
        return String.format(bucketTemplate, dateColumn);
    }

    /** Whether rows are bucketed at all. */
    public boolean isBucketed() {
        return this != ALL;
    }

    /**
     * A label a person would recognise for the bucket starting on this date.
     * Deliberately not derived in SQL, so formatting stays in one place.
     */
    public String label(LocalDate bucketStart, LocalDate rangeStart, LocalDate rangeEnd) {
        if (this == ALL || bucketStart == null) {
            return rangeStart + " to " + rangeEnd;
        }

        switch (this) {
            case DAY:
                return bucketStart.toString();
            case WEEK:
                return "Week of " + bucketStart + " (to " + bucketStart.plusDays(6) + ")";
            case MONTH:
                return bucketStart.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH)
                        + " " + bucketStart.getYear();
            case YEAR:
                return String.valueOf(bucketStart.getYear());
            default:
                return bucketStart.toString();
        }
    }

    /** The last date covered by the bucket starting on {@code bucketStart}. */
    public LocalDate bucketEnd(LocalDate bucketStart, LocalDate rangeEnd) {
        if (this == ALL || bucketStart == null) {
            return rangeEnd;
        }
        switch (this) {
            case DAY:
                return bucketStart;
            case WEEK:
                return bucketStart.plusDays(6);
            case MONTH:
                return bucketStart.withDayOfMonth(bucketStart.lengthOfMonth());
            case YEAR:
                return bucketStart.withDayOfYear(bucketStart.lengthOfYear());
            default:
                return bucketStart;
        }
    }
}
