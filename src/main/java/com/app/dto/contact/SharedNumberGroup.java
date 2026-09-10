package com.app.dto.contact;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

/**
 * One mobile number and every customer recorded against it.
 *
 * Grouped rather than listed flat because the decision is per number, not per
 * customer: somebody has to say which of these customers actually owns the phone,
 * and the others need their own. Production has 11 such numbers, one of them held
 * by seven customers, and one shared between a customer and the company's own
 * number from companyConfig.
 */
@Data
public class SharedNumberGroup {

    private String mobileNo;
    private int customerCount;
    /** Combined balance sitting behind this one number. */
    private BigDecimal totalBalance;
    private List<ContactIssueRow> customers;

    /**
     * True when two customers on this number have effectively the same name, which
     * means they are almost certainly one customer entered twice rather than two
     * people sharing a phone.
     *
     * This matters more than the phone number. "Salman Darfal" appears twice on
     * 7709399507, and "ABHIJIT JAMDADE" and "Abhijit jamdade" on 9545675607 -
     * each pair holding a separate balance, so that customer's true position is
     * split across two records and neither statement would be correct. These need
     * merging, not a second phone number.
     */
    private boolean likelyDuplicateCustomer;
    private String suggestion;
}
