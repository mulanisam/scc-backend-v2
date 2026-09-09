package com.app.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PurchaseDetailsDTO {
	private Double totalAmount;
    private Double paidAmount;
}
