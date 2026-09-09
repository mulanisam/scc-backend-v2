package com.app.dto;

import java.time.LocalDate;

import lombok.Data;
@Data
public class PurchasePaymentDTO {
	private LocalDate dateOfPurchase;
    private LocalDate dateOfTransaction;
    private String trans_id;
    private Double totalAmount;
    private Double paidAmount;
    private Double pendingPayment;
    private String comment;
    private Long supplier;
}
