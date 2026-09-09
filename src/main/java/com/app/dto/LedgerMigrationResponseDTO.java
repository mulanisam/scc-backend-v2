package com.app.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LedgerMigrationResponseDTO {
    private boolean success;
    private String message;
    private int customersProcessed;
    private int salesMigrated;
    private int ledgerEntriesCreated;
    private String error;
}
