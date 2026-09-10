package com.app.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class DcDetailDTO {
    private Integer srNo;
    private String dcNo;
    private Integer nos;
    private BigDecimal kilograms;
    private BigDecimal rate;
    private BigDecimal amount;

}