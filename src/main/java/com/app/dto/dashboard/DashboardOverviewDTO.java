package com.app.dto.dashboard;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * Everything the dashboard shows, in one response.
 *
 * The previous dashboard called four endpoints, three of which had never existed
 * - /dashboard/route-wise, /dashboard/high-balance-customers and
 * /dashboard/route-pending - and the one that did exist ignored the date it was
 * given and always answered for today, so the date picker on the screen did
 * nothing. One call that honours the date replaces all four.
 *
 * Day figures sit beside month- and year-to-date on purpose: on any day with no
 * trading recorded yet, a day-only dashboard is a screen of zeros.
 */
@Data
public class DashboardOverviewDTO {

    private LocalDate asOfDate;
    private LocalDateTime generatedAt;

    /** Latest date that actually has a sale, so an empty day can explain itself. */
    private LocalDate latestTradingDate;
    private boolean tradedOnAsOfDate;

    private DashboardSalesTotals day;
    private DashboardSalesTotals previousDay;
    private DashboardSalesTotals monthToDate;
    private DashboardSalesTotals yearToDate;

    private DashboardPurchaseTotals purchaseMonthToDate;
    private DashboardPurchaseTotals purchaseYearToDate;
    private DashboardMargin marginYearToDate;

    /** Ends on asOfDate; every day present, whether traded or not. */
    private List<DashboardTrendPoint> dailyTrend;

    private List<DashboardRouteRow> routesOnDay;
    private List<DashboardRouteRow> routesMonthToDate;
    private List<DashboardSupplierRow> suppliersYearToDate;

    private DashboardReceivables receivables;
    private DashboardExceptions exceptions;
}
