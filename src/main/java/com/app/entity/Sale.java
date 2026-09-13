package com.app.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonBackReference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class Sale extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDate date;
    private Long vehicleNo;
    private BigDecimal kilograms;
    private BigDecimal rate;
    private Integer birds;
    private BigDecimal amount;
    private BigDecimal payment;
    private BigDecimal pending;
    private String paymentMode;
    private String description;
    private boolean obsolete;
    private boolean smsSent = false;
   
    /**
     * The route this sale was delivered on.
     *
     * @JsonIgnoreProperties("cities") for the same reason City.route already carries it, and
     * its absence here was expensive twice over. A route serialises its whole city list, and
     * each of those cities its whole customer list - so one sale carried the route, 171
     * cities and several hundred customers with it. That is what made GET /user/sales return
     * <b>1.13 GB</b>, and with spring.jpa.open-in-view off it became an outright failure:
     * "failed to lazily initialize a collection of role: Route.cities - no Session".
     */
    @ManyToOne
    @JoinColumn(name = "route_id", nullable = false)
    @JsonIgnoreProperties({ "cities", "customers" })
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