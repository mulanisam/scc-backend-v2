package com.app.dto.dashboard;

import java.math.BigDecimal;

import lombok.Data;

/**
 * Bought against sold for one window.
 *
 * comparable is false when either side has no rows, which makes every figure here
 * meaningless rather than merely zero. This database holds 9 purchases against
 * 56,099 sales, so the dashboard has to say that instead of presenting an apparent
 * margin as fact.
 */
@Data
public class DashboardMargin {

    private BigDecimal buyRatePerKg;
    private BigDecimal sellRatePerKg;
    private BigDecimal marginPerKg;
    private BigDecimal weightLoss;
    private boolean comparable;
    private String note;
}
