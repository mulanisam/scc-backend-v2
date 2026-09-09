package com.app.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.web.multipart.MultipartFile;

import com.app.entity.DcDetail;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

 @Data
	@JsonInclude(JsonInclude.Include.NON_NULL)
	@JsonIgnoreProperties(ignoreUnknown = true)
	public class PurchaseEntryDto {

		//private Long id;
		private LocalDate entryDate;
		private Long vehicle;
	    private Long supplier;
	    private Long driver;
	    
	    private String branch;
	    private String farm;
	    private String supervisorName;
	    private Long supervisorPhoneNo;
	    private Double driverExpenses; 
	    private Double diesel;
	    private Double hamali;
	    private String notes;
	    private MultipartFile dcFile;
	    
	    private List<DcDetail> dcDetails;
	}
