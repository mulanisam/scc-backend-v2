package com.app.dto;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerPaymentDTO {
    private Long customerId;
    private LocalDate paymentDate;
    private Double amount;
    private String paymentMode; // CASH, UPI, CHEQUE, BANK_TRANSFER
    private String transactionReference;
    private String remarks;
    private String receivedBy;
}
