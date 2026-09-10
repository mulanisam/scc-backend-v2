package com.app.dto.dashboard;

import java.math.BigDecimal;

import lombok.Data;

/** One route's trading in the window. */
@Data
public class DashboardRouteRow {

    private Long routeId;
    private String routeName;
    private long tripCount;
    private long customerCount;
    private long birds;
    private BigDecimal weight;
    private BigDecimal amount;
    private BigDecimal received;
    private BigDecimal pending;
    private BigDecimal averageRate;
    /** This route's share of the window's amount, as a percentage. */
    private BigDecimal sharePercent;
    /** Ledger balance owed by the customers who traded on this route. */
    private BigDecimal outstanding;
}
