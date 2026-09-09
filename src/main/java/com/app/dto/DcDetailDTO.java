package com.app.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class DcDetailDTO {
    private Integer srNo;
    private String dcNo;
    private String nos;
    private String kilograms;
    private String rate;
    private Double amount;

}