package com.app.controller;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.CustomerLedgerDTO;
import com.app.dto.CustomerStatementDTO;
import com.app.service.LedgerService;

@RestController
@RequestMapping("/user/ledger")
public class LedgerController {

    private static final Logger logger = LoggerFactory.getLogger(LedgerController.class);

    @Autowired
    private LedgerService ledgerService;

    /**
     * Get customer ledger with optional date range
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<?> getCustomerLedger(
            @PathVariable Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        
        logger.info("Fetching ledger for customer: {}, from: {}, to: {}", customerId, startDate, endDate);
        
        try {
            List<CustomerLedgerDTO> ledger = ledgerService.getCustomerLedger(customerId, startDate, endDate);
            return ResponseEntity.ok(ledger);
        } catch (Exception e) {
            logger.error("Error fetching ledger: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to fetch ledger: " + e.getMessage());
        }
    }

    /**
     * The same transactions presented as an account statement: customer identity,
     * the balance brought forward into the period, each sale's birds, weight and
     * rate, and the closing figures.
     *
     * No try/catch here - GlobalExceptionHandler turns a missing customer into a
     * 404 and a reversed date range into a 400 carrying the message. The endpoint
     * above swallows both into a 500, which is why the screen could only ever say
     * "Failed to fetch ledger data".
     */
    @GetMapping("/customer/{customerId}/statement")
    public ResponseEntity<CustomerStatementDTO> getCustomerStatement(
            @PathVariable Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        logger.info("Statement requested for customer {}, {} to {}", customerId, startDate, endDate);
        return ResponseEntity.ok(ledgerService.getCustomerStatement(customerId, startDate, endDate));
    }
}
