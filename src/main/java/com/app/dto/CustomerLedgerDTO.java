package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import com.app.entity.CustomerLedger.TransactionType;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerLedgerDTO {
    private Long id;
    private Long customerId;
    private String customerName;
    private LocalDate transactionDate;
    private TransactionType transactionType;
    private String referenceType;
    private Long referenceId;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private BigDecimal runningBalance;
    private String description;
    private String paymentMode;
    private LocalDateTime createdAt;

    /**
     * Lombok names the getter isBackdated(), which Jackson publishes as
     * "backdated" - so the field the frontend was reading, entry.isBackdated,
     * was always undefined and the "Backdated" marker never appeared. Naming the
     * JSON property explicitly keeps the intended name on the wire.
     */
    @JsonProperty("isBackdated")
    private boolean isBackdated;

    // ---- sale detail, resolved from the referenced sale -------------------
    // The ledger row itself stores only money. A statement has to show what the
    // money was for, and until now that lived inside the description string
    // ("Sale - 25 birds, 45.500 kg"), which cannot be aligned into columns or
    // totalled. These are populated for SALE rows only.

    private Integer birds;
    private BigDecimal weight;
    private BigDecimal rate;
    private String routeName;
    private String driverName;
    private String vehicleNo;

    /** True when the referenced sale has been superseded by a correction. */
    private boolean obsolete;
}
