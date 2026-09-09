package com.app.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.ReportRequestDTO;
import com.app.dto.ReportResponseDTO;
import com.app.service.ReportService;

@RestController
@RequestMapping("/reports")
public class ReportsController {

    private static final Logger logger = LoggerFactory.getLogger(ReportsController.class);

    private final ReportService reportService;

    public ReportsController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/fetch")
    public ResponseEntity<ReportResponseDTO> fetchReport( @RequestBody ReportRequestDTO request) {
        logger.info("Received report request: {}", request);

        try {
            ReportResponseDTO reportData = reportService.generateReport(request);
            logger.info("Report generated successfully: {}", reportData);
            return ResponseEntity.ok(reportData);
        } catch (Exception ex) {
            logger.error("Error occurred while generating report", ex);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                                 .body(new ReportResponseDTO());
        }
    }
}
