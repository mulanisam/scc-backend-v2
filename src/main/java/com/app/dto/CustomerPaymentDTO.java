package com.app.dto;

import java.math.BigDecimal;
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
    private BigDecimal amount;
    private String paymentMode; // CASH, UPI, CHEQUE, BANK_TRANSFER
    private String transactionReference;
    private String remarks;
    private String receivedBy;

    /*
     * Whether to acknowledge this receipt to the customer, per channel.
     *
     * Two independent flags rather than one channel choice, matching the sales screen:
     * the operator decides per entry, and both can be on. A receipt was the one money
     * movement the customer was never told about - the daily message goes out on a sale
     * and says the balance, but a payment taken on its own sent nothing at all, so a
     * customer who settled in cash had no record of it until the next statement.
     */
    private boolean sendSms;
    private boolean sendWhatsapp;
}
