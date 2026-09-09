package com.app.dto;

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
    private Long partyVehicleId;
    private Integer birds;
    private Double kilograms;
    private Double rate;
    private Integer amount;
    private Integer payment;
    private Integer pending;
    private Integer balanceAmount;
    private String description;
}
