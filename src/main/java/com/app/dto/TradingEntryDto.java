package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import lombok.Data;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TradingEntryDto {
    private LocalDate date;
    private Long partyId;
    private Long supplierId;
    /** The registration number of the vehicle that carried the load. */
    private String vehicleNumber;
    private Integer birds;
    private BigDecimal kilograms;
    private BigDecimal rate;
    private BigDecimal amount;
    private BigDecimal payment;
    private BigDecimal pending;
    private BigDecimal balanceAmount;
    private String paymentMode;
    private String description;

    /*
     * Whether to tell the party what was delivered, per channel.
     *
     * Two independent flags, the same as the sales screen: the operator decides per
     * entry and both can be on. Route 9's eleven parties were messaged like any other
     * customer before they moved here, and four of them have a valid number - so the
     * toggles had to come with them or the move would have quietly stopped their
     * messages.
     */
    private boolean sendSms;
    private boolean sendWhatsapp;
}
