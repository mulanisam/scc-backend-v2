package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Money received from a wholesale party.
 *
 * Shaped after the trading screen's own form rather than after CustomerPaymentDTO, because
 * the screen identifies the payer by party and knows nothing about the customer row behind
 * it. The service resolves one to the other and then records it through exactly the same
 * path a retail receipt takes - a party's account is a customer ledger account, so a second
 * payment path would be a second version of the truth about what a party has paid.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TradingPaymentDto {

    private Long partyId;

    /** When the money was taken, not when it was typed in. */
    private LocalDate date;

    /**
     * The amount received.
     *
     * Named payment rather than amount to match the trading screen and the trading entry,
     * where amount means what the load was billed at. Two fields called amount meaning
     * different things on adjacent tabs is how a receipt gets entered as an invoice.
     */
    private BigDecimal payment;

    /** CASH, UPI, CHEQUE or BANK_TRANSFER. */
    private String paymentMode;

    /** Cheque number or UPI reference, whatever identifies it at the bank. */
    private String transactionId;

    private String description;

    /**
     * Whether to acknowledge the receipt, per channel.
     *
     * Both can be on, and they say different things: the approved DLT template for SMS
     * states the balance after the receipt, while the WhatsApp template states the amount
     * received. A party settling a large cash payment had no record of it until the next
     * statement.
     */
    private boolean sendSms;
    private boolean sendWhatsapp;
}
