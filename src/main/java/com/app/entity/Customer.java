package com.app.entity;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String mobileNo;
    private String address;
    private String shopName;
    private boolean obsolete;
    private double balanceAmount;
    
    // Credit limit management (optional)
    private boolean creditLimitEnabled = false;
    private Double creditLimit; // Nullable - only applies if creditLimitEnabled is true
   
    
//    @ManyToOne
//    @JoinColumn(name = "route_id", nullable = false)
//    private Route route;

    @ManyToOne
    @JoinColumn(name = "city_id", nullable = false)
    @JsonIgnoreProperties("customers")
    private City city;

}

