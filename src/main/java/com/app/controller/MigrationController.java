package com.app.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.LedgerMigrationResponseDTO;
import com.app.service.LedgerMigrationService;

@RestController
@RequestMapping("/admin/migration")
public class MigrationController {

    private static final Logger logger = LoggerFactory.getLogger(MigrationController.class);

    @Autowired
    private LedgerMigrationService migrationService;

    /**
     * Run one-time migration of existing sales to ledger
     * WARNING: This should only be run once after deploying the new system
     */
    @PostMapping("/ledger")
    public ResponseEntity<?> migrateLedger() {
        logger.info("Starting ledger migration...");
        
        try {
            LedgerMigrationResponseDTO response = migrationService.migrateExistingSalesToLedger();
            
            if (response.isSuccess()) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }
        } catch (Exception e) {
            logger.error("Migration failed: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Migration failed: " + e.getMessage());
        }
    }

    /**
     * Get migration status
     */
    @GetMapping("/status")
    public ResponseEntity<?> getMigrationStatus() {
        try {
            String status = migrationService.getMigrationStatus();
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            logger.error("Error fetching status: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to fetch status: " + e.getMessage());
        }
    }
}
