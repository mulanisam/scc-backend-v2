package com.app.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
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

    /*
     * No try/catch in this controller.
     *
     * Every method here used to catch Exception and answer
     * 500 "Failed to ...: " + e.getMessage(). Three things that cost:
     *
     *  - a payment for a customer id that does not exist was a 500, not a 404
     *  - the raw exception text went to the screen, driver messages and all
     *  - "Payment not found: " on a catch-all meant a genuine server fault was reported
     *    as a 404, so a real failure looked like a typo
     *
     * GlobalExceptionHandler now does it: ResourceNotFoundException becomes 404,
     * IllegalArgumentException and IllegalStateException become 400 with the message
     * intact, and anything unexpected becomes a 500 carrying a reference that appears on
     * every log line for that request.
     */

    /** Records a receipt, posts it to the ledger, and acknowledges it to the customer. */
    @PostMapping
    public ResponseEntity<CustomerPayment> createPayment(@RequestBody CustomerPaymentDTO paymentDTO) {
        logger.info("Recording a payment of {} for customer {}",
                paymentDTO.getAmount(), paymentDTO.getCustomerId());
        return ResponseEntity.ok(paymentService.createPayment(paymentDTO));
    }

    /** Every receipt for one customer, newest first. */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<CustomerPayment>> getCustomerPayments(@PathVariable Long customerId) {
        return ResponseEntity.ok(paymentService.getCustomerPayments(customerId));
    }

    /** Receipts taken between two dates. */
    @GetMapping("/date-range")
    public ResponseEntity<List<CustomerPayment>> getPaymentsByDateRange(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        return ResponseEntity.ok(paymentService.getPaymentsByDateRange(startDate, endDate));
    }

    @GetMapping("/{paymentId}")
    public ResponseEntity<CustomerPayment> getPaymentById(@PathVariable Long paymentId) {
        return ResponseEntity.ok(paymentService.getPaymentById(paymentId));
    }

    /**
     * Cancels a receipt.
     *
     * Soft-deleted and reversed rather than removed: the payment row is flagged and a
     * DEBIT_NOTE is posted, so the balance returns to what it was while the record of both
     * the receipt and its cancellation survives.
     */
    @DeleteMapping("/{paymentId}")
    public ResponseEntity<Map<String, String>> deletePayment(@PathVariable Long paymentId) {
        logger.warn("Cancelling payment {}", paymentId);
        paymentService.deletePayment(paymentId);
        return ResponseEntity.ok(Map.of("message", "Payment cancelled and the balance reversed."));
    }
}
