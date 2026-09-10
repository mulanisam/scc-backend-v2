package com.app.dto.report;

/**
 * The business dimension a summary report groups by.
 *
 * Each constant carries the id and name columns to group on. As with
 * {@link ReportPeriod}, these fragments reach the query only from this fixed
 * set, never from a request parameter.
 *
 * The report query always joins customer, city, route, driver and vehicle, so
 * every dimension is available without varying the FROM clause.
 */
public enum ReportGroupBy {

    ROUTE("r.id", "r.name", "Route"),
    CUSTOMER("cust.id", "cust.name", "Customer"),
    DRIVER("d.id", "d.name", "Driver"),
    VEHICLE("v.id", "v.vehicle_no", "Vehicle"),
    CITY("city.id", "city.name", "City"),

    /** No dimension: one row per period, totalling everything. */
    NONE(null, null, "All");

    private final String idColumn;
    private final String nameColumn;
    private final String label;

    ReportGroupBy(String idColumn, String nameColumn, String label) {
        this.idColumn = idColumn;
        this.nameColumn = nameColumn;
        this.label = label;
    }

    public boolean isGrouped() {
        return idColumn != null;
    }

    /** SQL for the dimension id, or a literal NULL when ungrouped. */
    public String idExpression() {
        return isGrouped() ? idColumn : "NULL";
    }

    /** SQL for the dimension name, or a literal when ungrouped. */
    public String nameExpression() {
        return isGrouped() ? nameColumn : "'All'";
    }

    /** Column header for this dimension. */
    public String label() {
        return label;
    }
}
