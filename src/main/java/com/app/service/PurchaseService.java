package com.app.service;

import java.util.List;

import org.springframework.web.multipart.MultipartFile;

import com.app.dto.PurchaseDTO;
import com.app.dto.PurchaseDetailsDTO;
import com.app.dto.PurchasePaymentDTO;
import com.app.entity.Purchase;
import com.app.entity.SupplierPaymentHist;

public interface PurchaseService {

	public Purchase createPurchase(PurchaseDTO purchaseDTO, List<MultipartFile> files);
	PurchaseDetailsDTO getPurchaseDetails(Long supplierId, String entryDate);
	SupplierPaymentHist savePurchasePayment(PurchasePaymentDTO purchasePaymentDto);
}
