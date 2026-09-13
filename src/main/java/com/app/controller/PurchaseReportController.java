package com.app.controller;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.purchase.SupplierAccount;
import com.app.dto.purchase.SupplierPayableRow;
import com.app.service.report.PurchaseReportService;

/**
 * What we have bought and what we still owe for it.
 *
 * Under /adminuser, the same line already drawn for the customer ledger and the trading one:
 * office staff are the people who answer a supplier asking what is outstanding, and they could
 * not before - nothing showed it. DRIVER is not included.
 *
 * Separate from PurchaseController, which records entries. A screen that reads and a screen
 * that writes have different audiences and different risks, and the purchase controller is a
 * multipart upload endpoint that should not also be the reporting surface.
 */
@RestController
@RequestMapping("/adminuser/purchases")
public class PurchaseReportController {

    private static final Logger logger = LoggerFactory.getLogger(PurchaseReportController.class);

    private final PurchaseReportService reportService;

    public PurchaseReportController(PurchaseReportService reportService) {
        this.reportService = reportService;
    }

    /**
     * Every supplier we have bought from, most owed first.
     *
     * The period bounds what was bought and paid within it; the outstanding column is always
     * current, because a balance is not a question about a date range. Unbounded by default,
     * which for this data is nine purchases.
     */
    @GetMapping("/payables")
    public ResponseEntity<List<SupplierPayableRow>> payables(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        logger.info("Supplier payables requested, {} to {}", from, to);
        return ResponseEntity.ok(reportService.payables(from, to));
    }

    /**
     * One supplier's account: the ledger, and the purchases behind it.
     *
     * Both in one response because a supplier query needs both - the ledger says what is owed
     * and how it got there, the purchases say what was received for it.
     */
    @GetMapping("/suppliers/{supplierId}")
    public ResponseEntity<SupplierAccount> account(
            @PathVariable Long supplierId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        logger.info("Supplier account requested for {}, {} to {}", supplierId, from, to);
        return ResponseEntity.ok(reportService.account(supplierId, from, to));
    }
}
