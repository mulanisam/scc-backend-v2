package com.app.controller;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.CustomerPaymentDTO;
import com.app.entity.CustomerPayment;
import com.app.service.PaymentService;

@RestController
@RequestMapping("/user/payments")
public class PaymentController {

    private static final Logger logger = LoggerFactory.getLogger(PaymentController.class);

    @Autowired
    private PaymentService paymentService;

    /**
     * Create a new payment entry
     */
    @PostMapping
    public ResponseEntity<?> createPayment(@RequestBody CustomerPaymentDTO paymentDTO) {
        logger.info("Creating payment: {}", paymentDTO);
        try {
            CustomerPayment payment = paymentService.createPayment(paymentDTO);
            return ResponseEntity.ok(payment);
        } catch (IllegalArgumentException e) {
            logger.error("Invalid payment data: {}", e.getMessage());
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            logger.error("Error creating payment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to create payment: " + e.getMessage());
        }
    }

    /**
     * Get all payments for a customer
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<?> getCustomerPayments(@PathVariable Long customerId) {
        logger.info("Fetching payments for customer ID: {}", customerId);
        try {
            List<CustomerPayment> payments = paymentService.getCustomerPayments(customerId);
            return ResponseEntity.ok(payments);
        } catch (Exception e) {
            logger.error("Error fetching payments: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to fetch payments: " + e.getMessage());
        }
    }

    /**
     * Get payments by date range
     */
    @GetMapping("/date-range")
    public ResponseEntity<?> getPaymentsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        logger.info("Fetching payments from {} to {}", startDate, endDate);
        try {
            List<CustomerPayment> payments = paymentService.getPaymentsByDateRange(startDate, endDate);
            return ResponseEntity.ok(payments);
        } catch (Exception e) {
            logger.error("Error fetching payments: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to fetch payments: " + e.getMessage());
        }
    }

    /**
     * Get payment by ID
     */
    @GetMapping("/{paymentId}")
    public ResponseEntity<?> getPaymentById(@PathVariable Long paymentId) {
        logger.info("Fetching payment with ID: {}", paymentId);
        try {
            CustomerPayment payment = paymentService.getPaymentById(paymentId);
            return ResponseEntity.ok(payment);
        } catch (Exception e) {
            logger.error("Error fetching payment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Payment not found: " + e.getMessage());
        }
    }

    /**
     * Delete (soft delete) a payment
     */
    @DeleteMapping("/{paymentId}")
    public ResponseEntity<?> deletePayment(@PathVariable Long paymentId) {
        logger.info("Deleting payment with ID: {}", paymentId);
        try {
            paymentService.deletePayment(paymentId);
            return ResponseEntity.ok("Payment deleted successfully");
        } catch (Exception e) {
            logger.error("Error deleting payment: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to delete payment: " + e.getMessage());
        }
    }
}
