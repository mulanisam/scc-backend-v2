package com.app.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DcDetailOld {
	    private Double kilograms;
	    private Long rate;
	    private Double amount;
	    private Long nos;
	    private String dcNo;
}
