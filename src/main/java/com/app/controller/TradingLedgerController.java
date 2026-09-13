package com.app.controller;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.trading.TradingEntryView;
import com.app.dto.trading.TradingPartyRow;
import com.app.dto.trading.TradingReport;
import com.app.entity.Party;
import com.app.exception.ResourceNotFoundException;
import com.app.repository.PartyRepository;
import com.app.repository.TradingEntryRepository;
import com.app.service.report.StatementPdfService;
import com.app.service.report.TradingReportService;

/**
 * The wholesale side's ledger and reports.
 *
 * Its own endpoints rather than filters on the retail ones, because the two are read
 * differently: a route report is per trip and per driver, trading is per party. Route 9
 * spent six months inside the route reports distorting exactly those figures, and keeping
 * them apart is the point of having moved it.
 *
 * Under /adminuser, so office staff and administrators both reach it - the same line drawn
 * for the customer ledger, which shows the same balances.
 */
@RestController
@RequestMapping("/adminuser/trading")
public class TradingLedgerController {

    private static final Logger logger = LoggerFactory.getLogger(TradingLedgerController.class);

    private static final int MAX_ROWS = 1000;

    private final TradingReportService reportService;
    private final TradingEntryRepository entryRepository;
    private final PartyRepository partyRepository;
    private final StatementPdfService statementPdfService;

    public TradingLedgerController(TradingReportService reportService,
                                   TradingEntryRepository entryRepository,
                                   PartyRepository partyRepository,
                                   StatementPdfService statementPdfService) {
        this.reportService = reportService;
        this.entryRepository = entryRepository;
        this.partyRepository = partyRepository;
        this.statementPdfService = statementPdfService;
    }

    /**
     * Every party with what they owe, heaviest debt first.
     *
     * The period only bounds the activity columns; the balance is always current, because
     * "what do they owe now" is not a question about a date range.
     */
    @GetMapping("/parties")
    public ResponseEntity<List<TradingPartyRow>> parties(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        logger.info("Trading party list requested, {} to {}", from, to);
        return ResponseEntity.ok(reportService.parties(from, to));
    }

    /** One party's loads and the account statement they were billed to. */
    @GetMapping("/parties/{partyId}/ledger")
    public ResponseEntity<TradingReportService.PartyLedger> ledger(
            @PathVariable Long partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        logger.info("Trading ledger requested for party {}, {} to {}", partyId, from, to);
        return ResponseEntity.ok(reportService.ledger(partyId, from, to));
    }

    /**
     * A party's statement as a PDF.
     *
     * The same generator the retail statement uses, on the same ledger account - so a
     * wholesale party's statement is the same document in the same layout, and a party who
     * was a route customer until this week sees no change in what arrives.
     */
    @GetMapping(value = "/parties/{partyId}/statement.pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> statementPdf(
            @PathVariable Long partyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {

        Party party = partyRepository.findById(partyId)
                .orElseThrow(() -> new ResourceNotFoundException("Party " + partyId + " was not found."));

        if (party.getCustomer() == null) {
            throw new IllegalStateException(party.getName()
                    + " has no ledger account, so there is no statement to produce."
                    + " Link the party to a customer first.");
        }

        StatementPdfService.Document document =
                statementPdfService.render(party.getCustomer().getId(), startDate, endDate);

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(document.fileName()).build().toString())
                .cacheControl(CacheControl.noStore())
                .body(document.bytes());
    }

    /** The trading day book: every party's entries over a period, newest first. */
    @GetMapping("/entries")
    public ResponseEntity<List<TradingEntryView>> entries(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "200") int limit) {

        return ResponseEntity.ok(entryRepository
                .findForPeriod(from, to, Limit.of(Math.max(1, Math.min(limit, MAX_ROWS))))
                .stream()
                .map(TradingEntryView::of)
                .toList());
    }

    /** What the wholesale side did over a period, by party and by day. */
    @GetMapping("/report")
    public ResponseEntity<TradingReport> report(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        logger.info("Trading report requested, {} to {}", from, to);
        return ResponseEntity.ok(reportService.report(from, to));
    }
}
