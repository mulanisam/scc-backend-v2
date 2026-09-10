package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SalesBulkEntryDto {

	//private Long id;
    @NotNull(message = "Sale date is required")
    private LocalDate date;
    @NotNull(message = "Vehicle is required")
    private Long vehicleNo;
    @NotNull(message = "Route is required")
    private Long route;
    @NotNull(message = "Driver is required")
    private Long driver;
    @NotEmpty(message = "At least one customer line is required")
    private List<@Valid SaleLineDto> salesDetails;
    
    @NotNull(message = "Total birds is required")
    @PositiveOrZero(message = "Total birds cannot be negative")
    private Integer totalBirds;
    @NotNull(message = "Mortality is required")
    @PositiveOrZero(message = "Mortality cannot be negative")
    private Integer mortality;
    @NotNull(message = "Return to farm is required")
    @PositiveOrZero(message = "Return to farm cannot be negative")
    private Integer returnToFarm;
    private String description;
    private Integer totalBirdSale;
    private BigDecimal totalKilogramSale;
    private BigDecimal totalAmount;
    private BigDecimal totalPaymentReceived;
    private BigDecimal totalPending;
    private boolean sendSms;
    
}
