package com.app.dto;

import java.util.LinkedHashMap;
import java.util.List;

import lombok.Data;

@Data
public class ReportResponseDTO {

	private String errorMessage;
    private List<LinkedHashMap<String, Object>> data;
}
