package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.Data;
@Data
public class PurchasePaymentDTO {
	/**
	 * Which purchase this payment settles.
	 *
	 * Preferred over dateOfPurchase, and the reason it was added: a supplier with two
	 * purchases on one date could not be paid at all, because the lookup by date expected
	 * exactly one row and threw on two. The payables screen lists a supplier's purchases with
	 * their ids, so it sends this and the ambiguity never arises.
	 */
	private Long purchaseId;

	/** The older way in, kept working: resolved by supplier and date when no id is given. */
	private LocalDate dateOfPurchase;
    private LocalDate dateOfTransaction;
    private String trans_id;
    /**
     * What the purchase was billed at.
     *
     * Informational only now. The service reads the purchase's own total instead, because a
     * figure the browser supplies for the bill is a display value - and when it disagreed
     * with the purchase, the payment history recorded the browser's version.
     */
    private BigDecimal totalAmount;
    private BigDecimal paidAmount;
    /** Also informational: what remains is derived from the purchase and what it has been paid. */
    private BigDecimal pendingPayment;
    private String comment;
    private Long supplier;
}
