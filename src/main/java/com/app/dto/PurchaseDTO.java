package com.app.dto;

import java.util.List;

import lombok.Data;

@Data
public class PurchaseDTO {
    private String entryDate;
    private Long vehicle;
    private Long driver;
    private Long supplier;
    private String branch;
    private String farm;
    private String supervisorName;
    private String supervisorPhoneNo;
    private String driverExpense;
    private String diesel;
    private String hamali;
    private String notes;
    private List<DcDetailDTO> dcDetails;

}