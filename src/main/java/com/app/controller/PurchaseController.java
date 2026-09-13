package com.app.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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

import com.app.exception.ResourceNotFoundException;

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

    /**
     * Records a purchase, with a scanned DC per line.
     *
     * No try/catch. Every failure here used to come back as a 400 reading "Failed to process
     * request" - a supplier that does not exist, a line whose amount does not add up, a
     * malformed date and a disk that could not be written to, all the same sentence. The
     * handler turns each into its own status with its own message, and the operator can act
     * on "DC line 2 does not add up" without telephoning anybody.
     *
     * The files list is positional: files[i] is the scan for dcDetails[i]. A line with no scan
     * is allowed - the DC often arrives later - and a short list no longer walks off the end,
     * which it did with IndexOutOfBoundsException reported as that same generic 400.
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createPurchase(
            @RequestParam("purchaseEntry") String purchaseJson,
            @RequestParam(value = "files", required = false) List<MultipartFile> files)
            throws IOException {

        PurchaseDTO purchaseDTO = new ObjectMapper().readValue(purchaseJson, PurchaseDTO.class);
        logger.info("Purchase entry requested for supplier {} dated {}",
                purchaseDTO.getSupplier(), purchaseDTO.getEntryDate());

        Purchase purchase = purchaseService.createPurchase(purchaseDTO,
                files == null ? List.of() : files);

        logger.info("Purchase created with ID: {}", purchase.getId());
        // A body with the figures in it, rather than a sentence with an id glued on: the
        // screen can show what was recorded and what the supplier is now owed.
        return ResponseEntity.ok(Map.of(
                "id", purchase.getId(),
                "totalAmount", purchase.getTotalAmount(),
                "lines", purchase.getDcDetails().size(),
                "message", "Purchase recorded and billed to " + purchase.getSupplier().getName()));
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
    
    /**
     * Records money paid to a supplier.
     *
     * Same reasoning as the entry above: the catch-all reported a supplier that does not
     * exist, a payment of zero and two purchases sharing a date as one indistinguishable
     * "Failed to process request". The last of those was not hypothetical - it is what paying
     * Komarla Agrovet for 2025-09-06 did every time.
     */
    @PostMapping("/payment")
    public ResponseEntity<Map<String, Object>> purchasePayment(
            @RequestBody PurchasePaymentDTO paymentHistDto) {

        logger.info("Supplier payment requested for supplier {} on {}",
                paymentHistDto.getSupplier(), paymentHistDto.getDateOfTransaction());

        SupplierPaymentHist saved = purchaseService.savePurchasePayment(paymentHistDto);

        return ResponseEntity.ok(Map.of(
                "id", saved.getId(),
                "paidAmount", saved.getPaidAmount(),
                "stillOutstanding", saved.getPendingPayment(),
                "message", "Payment recorded."));
    }
}
