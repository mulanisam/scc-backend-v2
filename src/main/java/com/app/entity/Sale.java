package com.app.entity;

import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonBackReference;

import jakarta.persistence.Entity;
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
public class Sale {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDate date;
    private Long vehicleNo;
    private Double kilograms;
    private Double rate;
    private Integer birds;
    private Integer amount;
    private Integer payment;
    private Integer pending;
    private Integer balancePending;
    private String paymentMode;
    private String description;
    private boolean obsolete;
    private boolean smsSent = false;
   
    @ManyToOne
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;
    
    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;

    @ManyToOne
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;
    
    @ManyToOne
    @JoinColumn(name = "sale_details_id")
    @JsonBackReference
    private SaleDetails saleDetails;

}