package com.app.dto.report;

import java.time.LocalDate;
import java.util.List;

import lombok.Data;

/**
 * A rendered report: its rows, its totals, and enough context for a heading and
 * an export filename.
 */
@Data
public class SalesReportResponse {

    private String title;
    private LocalDate startDate;
    private LocalDate endDate;

    /** Filters that were actually applied, for the report heading. */
    private List<String> appliedFilters;

    private String periodLabel;
    private String groupByLabel;

    private List<SalesDetailRow> detail;
    private List<SalesSummaryRow> summary;

    private ReportTotals totals;
}
