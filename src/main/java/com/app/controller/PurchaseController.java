package com.app.controller;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.app.dto.PurchaseDTO;
import com.app.dto.PurchaseDetailsDTO;
import com.app.dto.PurchasePaymentDTO;
import com.app.entity.Purchase;
import com.app.entity.SupplierPaymentHist;
import com.app.repository.DriverRepository;
import com.app.repository.PurchaseRepository;
import com.app.repository.SupplierPayHistRepository;
import com.app.repository.SupplierRepository;
import com.app.service.PurchaseService;
import com.fasterxml.jackson.databind.ObjectMapper;

import cutsomException.ResourceNotFoundException;

@RestController
@RequestMapping("/user/purchases")
public class PurchaseController {

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private PurchaseService purchaseService;
    
    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private DriverRepository driverRepository;
    

    private static final Logger logger = LoggerFactory.getLogger(PurchaseController.class);

    @GetMapping
    public List<Purchase> getAllPurchases() {
        logger.info("Fetching all purchases");
        return purchaseRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<String> createPurchase(@RequestParam("purchaseEntry") String purchaseJson,
            @RequestParam("files") List<MultipartFile> files) throws IOException {
        
        try {
            logger.info("Creating purchase with JSON: {}", purchaseJson);
            ObjectMapper objectMapper = new ObjectMapper();
            PurchaseDTO purchaseDTO = objectMapper.readValue(purchaseJson, PurchaseDTO.class);
            
            Purchase purchase = purchaseService.createPurchase(purchaseDTO, files);
            logger.info("Purchase created successfully with ID: {}", purchase.getId());
            return ResponseEntity.ok("Purchase Created successfully! ID-" + purchase.getId());

        } catch (Exception e) {
            logger.error("Failed to process request", e);
            return ResponseEntity.badRequest().body("Failed to process request");
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Purchase> getPurchaseById(@PathVariable Long id) {
        logger.info("Fetching purchase with ID: {}", id);
        Purchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found with id " + id));
        return ResponseEntity.ok(purchase);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Purchase> updatePurchase(@PathVariable Long id, @RequestBody Purchase purchaseDetails) {
        logger.info("Updating purchase with ID: {}", id);
        Purchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found with id " + id));

        purchase.setEntryDate(purchaseDetails.getEntryDate());
        purchase.setSupplier(supplierRepository.findById(purchaseDetails.getSupplier().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier not found")));
        purchase.setDriver(driverRepository.findById(purchaseDetails.getDriver().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Driver not found")));

        Purchase updatedPurchase = purchaseRepository.save(purchase);
        logger.info("Purchase updated successfully with ID: {}", updatedPurchase.getId());
        return ResponseEntity.ok(updatedPurchase);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePurchase(@PathVariable Long id) {
        logger.info("Deleting purchase with ID: {}", id);
        Purchase purchase = purchaseRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found with id " + id));

        purchaseRepository.delete(purchase);
        logger.info("Purchase deleted successfully with ID: {}", id);
        return ResponseEntity.noContent().build();
    }
    
    @GetMapping("/getDetails")
    public PurchaseDetailsDTO getPurchaseDetails(@RequestParam Long supplierId, @RequestParam String entryDate) {
        return purchaseService.getPurchaseDetails(supplierId, entryDate);
    }
    
    @PostMapping("/payment")
    public ResponseEntity<String> purchasePayment(@RequestBody PurchasePaymentDTO  paymentHistDto) throws IOException {
        
        try {
            logger.info("Creating purchase Payment SupplierPaymentHist: {}", paymentHistDto); 
            SupplierPaymentHist paymentHistSaved=purchaseService.savePurchasePayment(paymentHistDto);
            
            return ResponseEntity.ok("Purchase Payment Created successfully! ID-" + paymentHistSaved.getId());

        } catch (Exception e) {
            logger.error("Failed to process request", e);
            return ResponseEntity.badRequest().body("Failed to process request");
        }
    }
}
