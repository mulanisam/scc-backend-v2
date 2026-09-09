package com.app.dto;

import java.time.LocalDate;

import lombok.Data;

@Data
public class ReportRequestDTO {
	private String reportType;
    private String subType;
    private LocalDate startDate;
    private LocalDate endDate;
    private String subTypeId;
}
