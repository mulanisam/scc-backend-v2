package com.app.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIdentityInfo;
import com.fasterxml.jackson.annotation.JsonManagedReference;
import com.fasterxml.jackson.annotation.ObjectIdGenerators;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
@JsonIdentityInfo(generator = ObjectIdGenerators.PropertyGenerator.class, property = "id")
public class SaleDetails extends AuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "saleDetails")
    @JsonManagedReference
    private List<Sale> salesEntries;
    
    private LocalDate date;
    
    @ManyToOne
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;
    
    @ManyToOne
    @JoinColumn(name = "vehicle_id", nullable = false)
    private Vehicle vehicle;

    @ManyToOne
    @JoinColumn(name = "driver_id", nullable = false)
    private Driver driver;
    
    private Integer totalBirds;
    private Integer mortality;
    private Integer returnToFarm;
    private String description;
    private Integer totalBirdSale;
    private BigDecimal totalKilogramSale;
    private BigDecimal totalAmount;
    private BigDecimal totalPaymentReceived;
    private BigDecimal totalPending;

    /**
     * Weight loaded at the farm. Null for every trip recorded before this was
     * captured, so weight loss is reported only where the figure is known.
     */
    private BigDecimal loadedKilograms;

    /**
     * Set when a trip corrects another rather than being a fresh entry. Such a
     * trip legitimately shares its date, route, vehicle and driver with the one
     * it corrects, which is why that combination carries no unique constraint.
     */
    private boolean isCorrection;

    private String correctionNote;
}
