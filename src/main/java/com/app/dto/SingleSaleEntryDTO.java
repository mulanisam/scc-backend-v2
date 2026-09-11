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
    private boolean sendWAmsg;
    /** Independent of sendWAmsg - both can be requested for one sale. */
    private boolean sendSms;
}
