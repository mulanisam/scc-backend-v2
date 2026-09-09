package com.app.entity;

import java.math.BigDecimal;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DcDetail {
	   @Id
	    @GeneratedValue(strategy = GenerationType.IDENTITY)
	    private Long id;
	   
	    private String dcNo;
	    private String nos;
	    private String kilograms;
	    private String rate;
	    private Double amount;

	    @ManyToOne(fetch = FetchType.LAZY)
	    @JoinColumn(name = "purchase_id")
	    private Purchase purchase;

	    private String filePath;
}