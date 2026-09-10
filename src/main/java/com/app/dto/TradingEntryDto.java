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
    private Long partyVehicleId;
    private Integer birds;
    private BigDecimal kilograms;
    private BigDecimal rate;
    private BigDecimal amount;
    private BigDecimal payment;
    private BigDecimal pending;
    private BigDecimal balanceAmount;
    private String description;
}
