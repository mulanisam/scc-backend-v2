package com.app.dto.dashboard;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/** A customer who owes money, and how long since they last paid anything. */
@Data
public class DashboardCustomerBalanceRow {

    private Long customerId;
    private String customerName;
    private String shopName;
    private String cityName;
    private BigDecimal balance;
    private BigDecimal creditLimit;
    private boolean overCreditLimit;
    private LocalDate lastPaymentDate;
    private Long daysSinceLastPayment;
    private LocalDate lastSaleDate;
}
