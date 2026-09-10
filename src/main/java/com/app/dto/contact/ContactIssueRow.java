package com.app.dto.contact;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;

/**
 * One customer whose mobile number cannot be sent to, with enough context to
 * decide whether to bother chasing it.
 *
 * Balance and last-sale date are here for ordering the work: 89 customers who owe
 * money between them cannot be reached at all, and that is where the value is -
 * a dormant customer with no number and no balance can wait.
 */
@Data
public class ContactIssueRow {

    private Long customerId;
    private String customerName;
    private String shopName;
    private String cityName;

    /** As recorded, unchanged, so an operator sees exactly what is stored. */
    private String mobileNo;
    /** MISSING, TOO_SHORT, BAD_PREFIX, PLACEHOLDER, SHARED, ... */
    private String status;
    private String reason;

    private BigDecimal balance;
    private LocalDate lastSaleDate;
    private long saleCount;

    /**
     * For a shared number: the other customers on it. A statement sent to a number
     * held by seven people discloses seven balances, so these are listed together
     * rather than as seven unrelated rows.
     */
    private java.util.List<String> sharedWith;
}
