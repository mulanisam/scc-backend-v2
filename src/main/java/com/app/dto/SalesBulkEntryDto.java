package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SalesBulkEntryDto {

	//private Long id;
    private LocalDate date;
    private Long vehicleNo;
    private Long route;
    private Long driver;
    private List<SaleLineDto> salesDetails;
    
    private Integer totalBirds;
    private Integer mortality;
    private Integer returnToFarm;
    private String description;
    private Integer totalBirdSale;
    private BigDecimal totalKilogramSale;
    private BigDecimal totalAmount;
    private BigDecimal totalPaymentReceived;
    private BigDecimal totalPending;
    private boolean sendSms;
    
}
