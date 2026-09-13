package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SingleSaleEntryDTO {
    private LocalDate date;
    private Long customerId;
    private Long routeId;
    private Long vehicleId;
    private Long driverId;
    private BigDecimal kilograms;
    private BigDecimal rate;
    private Integer birds;
    private BigDecimal amount;
    private BigDecimal payment; // Payment received at time of sale
    private String paymentMode;
    private String description;
    /*
     * Named to match the bulk, trading and payment DTOs.
     *
     * This was sendWAmsg, which nothing ever sent: the single sale screen offered no
     * WhatsApp switch at all, so the field sat unread while the same flag on every other
     * entry screen was called sendWhatsapp. One name for one thing, or the next screen wired
     * up guesses wrong and silently sends nothing.
     */
    private boolean sendWhatsapp;
    /** Independent of sendWhatsapp - both can be requested for one sale. */
    private boolean sendSms;
}
