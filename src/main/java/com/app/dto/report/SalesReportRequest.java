package com.app.dto.report;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Filters for a sales report.
 *
 * Every filter is optional except the date range, and they combine: a request
 * naming a route and a driver returns that driver's sales on that route. This is
 * what lets one endpoint serve the customer-, route- and driver-wise views
 * rather than a separate stub per dimension, which is how the previous report
 * service ended up with four unimplemented branches.
 */
@Data
public class SalesReportRequest {

    @NotNull(message = "Start date is required")
    private LocalDate startDate;

    @NotNull(message = "End date is required")
    private LocalDate endDate;

    private Long routeId;
    private Long customerId;
    private Long driverId;
    private Long vehicleId;
    private Long cityId;

    /** Time bucketing for summary reports. Defaults to the whole range. */
    private ReportPeriod period = ReportPeriod.ALL;

    /** Business dimension for summary reports. Defaults to no dimension. */
    private ReportGroupBy groupBy = ReportGroupBy.NONE;

    /** Exclude customers marked obsolete. Defaults to including everything. */
    private boolean excludeObsolete = false;

    public ReportPeriod getPeriod() {
        return period == null ? ReportPeriod.ALL : period;
    }

    public ReportGroupBy getGroupBy() {
        return groupBy == null ? ReportGroupBy.NONE : groupBy;
    }
}
