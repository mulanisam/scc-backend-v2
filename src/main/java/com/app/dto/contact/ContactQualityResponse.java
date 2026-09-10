package com.app.dto.contact;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * The state of the customer contact book, and what stands between it and being
 * able to send statements over WhatsApp.
 *
 * The counts are the headline because they are the thing that decides whether
 * messaging can be switched on at all: a statement carries a balance, so it can
 * only go to a number known to belong to one customer.
 */
@Data
public class ContactQualityResponse {

    private long totalCustomers;
    private long activeCustomers;

    /** Ten digits, Indian prefix, not a placeholder, not shared. */
    private long reachable;
    /** Everything that needs a human to supply a number. */
    private long unusable;
    /** Valid numbers that more than one customer shares. */
    private long onSharedNumbers;
    private long sharedNumberCount;

    /** Owed by customers who cannot be contacted. The reason to do this work. */
    private BigDecimal unreachableBalance;
    /** Owed by customers sharing a number - reachable, but not privately. */
    private BigDecimal sharedNumberBalance;

    /** Bad or missing numbers, customers who owe the most first. */
    private List<ContactIssueRow> unusableNumbers;
    /** Grouped by number, so each group is read as one decision. */
    private List<SharedNumberGroup> sharedNumbers;
}
