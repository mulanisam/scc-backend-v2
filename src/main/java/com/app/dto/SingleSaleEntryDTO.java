package com.app.dto;

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
    private Double kilograms;
    private Double rate;
    private Integer birds;
    private Integer amount;
    private Integer payment; // Payment received at time of sale
    private String paymentMode;
    private String description;
    private boolean sendWAmsg;
}
