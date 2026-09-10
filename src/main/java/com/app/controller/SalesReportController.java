package com.app.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.report.SalesReportRequest;
import com.app.dto.report.SalesReportResponse;
import com.app.service.SalesReportService;

import jakarta.validation.Valid;

/**
 * Sales reporting.
 *
 * Replaces the single /reports/fetch endpoint, whose service had six report
 * dimensions of which four were unimplemented stubs returning empty results, and
 * one whose SQL ignored the date range it was given.
 *
 * Exceptions propagate to GlobalExceptionHandler: an invalid range returns 400
 * with the reason rather than a 500 carrying an empty report, which is what the
 * old controller did.
 */
@RestController
@RequestMapping("/reports/sales")
public class SalesReportController {

    private static final Logger logger = LoggerFactory.getLogger(SalesReportController.class);

    @Autowired
    private SalesReportService salesReportService;

    /**
     * Transaction lines. Any combination of route, customer, driver, vehicle and
     * city narrows the result, so this one endpoint serves the customer-, route-
     * and driver-wise detail views.
     */
    @PostMapping("/detail")
    public ResponseEntity<SalesReportResponse> detail(@Valid @RequestBody SalesReportRequest request) {
        logger.info("Detail report requested: {}", request);
        return ResponseEntity.ok(salesReportService.detailReport(request));
    }

    /**
     * Aggregates per time bucket crossed with one dimension.
     *
     * period=DAY|WEEK|MONTH|YEAR|ALL, groupBy=ROUTE|CUSTOMER|DRIVER|VEHICLE|CITY|NONE.
     * For example period=WEEK with groupBy=CUSTOMER and a routeId gives the weekly
     * birds, weight, amount, pending and closing balance for every customer on
     * that route.
     */
    @PostMapping("/summary")
    public ResponseEntity<SalesReportResponse> summary(@Valid @RequestBody SalesReportRequest request) {
        logger.info("Summary report requested: {}", request);
        return ResponseEntity.ok(salesReportService.summaryReport(request));
    }
}
