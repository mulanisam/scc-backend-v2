package com.app.controller;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.CustomerLedgerDTO;
import com.app.dto.CustomerStatementDTO;
import com.app.service.LedgerService;
import com.app.service.report.StatementPdfService;

@RestController
@RequestMapping("/user/ledger")
public class LedgerController {

    private static final Logger logger = LoggerFactory.getLogger(LedgerController.class);

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private StatementPdfService statementPdfService;

    /**
     * The ledger rows for one customer, optionally within a date range.
     *
     * No try/catch: GlobalExceptionHandler turns a missing customer into a 404 and a
     * reversed date range into a 400, both carrying the message. The block that used to be
     * here caught everything and answered
     * {@code 500 "Failed to fetch ledger: " + e.getMessage()}, which turned a mistyped
     * customer id into a server error, put the raw exception text on the screen, and is why
     * the ledger page could only ever say "Failed to fetch ledger data".
     */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<CustomerLedgerDTO>> getCustomerLedger(
            @PathVariable Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        logger.info("Fetching ledger for customer: {}, from: {}, to: {}", customerId, startDate, endDate);
        return ResponseEntity.ok(ledgerService.getCustomerLedger(customerId, startDate, endDate));
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

    /**
     * The same statement as a PDF, rendered here rather than in the browser.
     *
     * It moved off the client because the weekly WhatsApp statement needs one and a
     * scheduled job has no browser to draw it in. Having both come from this endpoint
     * means the document a customer downloads and the document they are sent are the
     * same file, which is the only way "is this the same statement" has a clean answer.
     *
     * inline rather than attachment, so a browser opens it in a tab and the sender can
     * read what is about to go out; the filename is still offered for a save.
     */
    @GetMapping(value = "/customer/{customerId}/statement.pdf",
                produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> getCustomerStatementPdf(
            @PathVariable Long customerId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        logger.info("Statement PDF requested for customer {}, {} to {}", customerId, startDate, endDate);

        StatementPdfService.Document document =
                statementPdfService.render(customerId, startDate, endDate);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                // build() before toString(): filename() returns the builder, and
                // stringifying that puts "ContentDisposition$BuilderImpl@4e06d466" in
                // the header, which leaves the browser to invent a filename.
                //
                // No charset argument. The name is ASCII by construction - the slug
                // keeps only letters, digits and hyphens - and naming a charset makes
                // Spring emit the RFC 2047 form, "=?UTF-8?Q?statement-...?=", which a
                // client then has to decode to get back the plain name it started as.
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline()
                                .filename(document.fileName())
                                .build()
                                .toString())
                // The figures change whenever a sale or payment is entered, and a stale
                // statement is worse than a slow one.
                .cacheControl(CacheControl.noStore())
                .body(document.bytes());
    }
}
