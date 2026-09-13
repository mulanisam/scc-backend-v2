package com.app.entity;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnore;

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
	    private Integer nos;
	    private BigDecimal kilograms;
	    private BigDecimal rate;
	    private BigDecimal amount;

	    /**
	     * The purchase this line belongs to.
	     *
	     * @JsonIgnore because it is the back half of the relationship: a purchase serialises
	     * its lines, and a line serialising its purchase is how the two recurse into each
	     * other until the response stops. With open-in-view off it would fail on the
	     * uninitialised proxy first, which is a different symptom of the same mistake.
	     */
	    @JsonIgnore
	    @ManyToOne(fetch = FetchType.LAZY)
	    @JoinColumn(name = "purchase_id")
	    private Purchase purchase;

	    private String filePath;
}