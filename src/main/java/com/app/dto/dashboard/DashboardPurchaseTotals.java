package com.app.dto.dashboard;

import java.math.BigDecimal;

import lombok.Data;

/** Purchase side of one window. */
@Data
public class DashboardPurchaseTotals {

    private String label;
    private long purchaseCount;
    private long birdsBought;
    private BigDecimal weightBought;
    private BigDecimal amount;
    private BigDecimal paid;
    private BigDecimal owed;
    /** Diesel, driver expense and hamali on the purchase trips. */
    private BigDecimal expenses;
    /** amount / weightBought. */
    private BigDecimal averageRate;
}
