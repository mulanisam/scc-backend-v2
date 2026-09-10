package com.app.dto.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/** One supplier's purchases in the window. */
@Data
public class DashboardSupplierRow {

    private Long supplierId;
    private String supplierName;
    private long purchaseCount;
    private long birds;
    private BigDecimal weight;
    private BigDecimal amount;
    private BigDecimal paid;
    private BigDecimal owed;
    private BigDecimal averageRate;
    private LocalDate lastPurchaseDate;
}
