package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

/**
 * A customer account statement: who it is for, what period it covers, the
 * balance brought forward, the transactions, and the closing figures.
 *
 * The plain ledger endpoint returns only the rows inside the date range, so a
 * filtered ledger began mid-stream with a running balance nothing on the page
 * explained. Everything a statement needs to stand on its own is assembled here
 * once, server-side, so the screen and the PDF cannot disagree about it.
 */
@Data
public class CustomerStatementDTO {

    private Long customerId;
    private String customerName;
    private String shopName;
    private String mobileNo;
    private String address;
    private String cityName;
    private boolean obsolete;
    private BigDecimal creditLimit;
    private boolean creditLimitEnabled;

    /** Null when the caller asked for the whole history. */
    private LocalDate startDate;
    private LocalDate endDate;
    /** Date of the earliest and latest row actually returned. */
    private LocalDate firstTransactionDate;
    private LocalDate lastTransactionDate;

    /**
     * Running balance carried by the last row before startDate. Zero when the
     * period is open-ended or nothing precedes it.
     */
    private BigDecimal openingBalance;

    private List<CustomerLedgerDTO> entries;
    private LedgerStatementTotals totals;

    private LocalDateTime generatedAt;
}
