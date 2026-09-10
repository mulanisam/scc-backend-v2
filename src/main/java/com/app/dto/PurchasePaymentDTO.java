package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;
@Data
public class PurchasePaymentDTO {
	private LocalDate dateOfPurchase;
    private LocalDate dateOfTransaction;
    private String trans_id;
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    private BigDecimal pendingPayment;
    private String comment;
    private Long supplier;
}
