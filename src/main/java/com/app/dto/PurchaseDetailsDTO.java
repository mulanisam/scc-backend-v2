package com.app.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PurchaseDetailsDTO {
	private BigDecimal totalAmount;
    private BigDecimal paidAmount;
}
