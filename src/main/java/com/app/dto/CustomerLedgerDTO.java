package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.app.entity.CustomerLedger.TransactionType;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerLedgerDTO {
    private Long id;
    private Long customerId;
    private String customerName;
    private LocalDate transactionDate;
    private TransactionType transactionType;
    private String referenceType;
    private Long referenceId;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private BigDecimal runningBalance;
    private String description;
    private String paymentMode;
    private LocalDateTime createdAt;
    private boolean isBackdated;
}
