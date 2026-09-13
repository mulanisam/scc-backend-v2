package com.app.entity;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * A loaded vehicle going out on the wholesale side.
 *
 * The same shape as a retail trip and for the same reason: 571 wholesale sales came off
 * 61 loads, so a vehicle goes out with birds on it and delivers to several parties. What
 * it does not have is a route, because trading is not a round - that was the fiction
 * "Route no 9 Trading" existed to maintain, and moving this business into Trading is what
 * let the fake route go.
 *
 * The bird invariant applies here exactly as it does to a retail trip:
 * totalBirds = totalBirdSale + mortality + returnToFarm.
 */
@Entity
@Table(name = "trading_trips")
@Data
@EqualsAndHashCode(callSuper = false)
public class TradingTrip extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    @ManyToOne
    @JoinColumn(name = "vehicle_id")
    private Vehicle vehicle;

    @ManyToOne
    @JoinColumn(name = "driver_id")
    private Driver driver;

    /** Loaded onto the vehicle at the farm. */
    private Integer totalBirds;
    private Integer mortality;
    private Integer returnToFarm;
    private BigDecimal loadedKilograms;

    private Integer totalBirdSale;
    private BigDecimal totalKilogramSale;
    private BigDecimal totalAmount;
    private BigDecimal totalPaymentReceived;
    private BigDecimal totalPending;

    private String description;

    /**
     * Marks this load as a correction of an earlier one.
     *
     * Carried over from the retail trip for the same reason: the policy on a
     * double-entered trip is to keep both and mark the later one, never to delete.
     */
    @Column(name = "is_correction", nullable = false)
    private boolean correction = false;

    @Column(length = 500)
    private String correctionNote;
}
