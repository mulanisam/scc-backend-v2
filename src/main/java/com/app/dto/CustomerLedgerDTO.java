package com.app.dto;

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
    private Double debitAmount;
    private Double creditAmount;
    private Double runningBalance;
    private String description;
    private String paymentMode;
    private LocalDateTime createdAt;
    private boolean isBackdated;
}
