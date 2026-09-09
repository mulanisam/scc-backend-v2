package com.app.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

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
    private List<Map<String, Object>> salesDetails;
    
    private Integer totalBirds;
    private Integer mortality;
    private Integer returnToFarm;
    private String description;
    private Integer totalBirdSale;
    private Integer totalKilogramSale;
    private Integer totalAmount;
    private Integer totalPaymentReceived;
    private Integer totalPending;
    private boolean sendSms;
    
}
