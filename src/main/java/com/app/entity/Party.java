package com.app.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "parties")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Party {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "owner")
    private String owner;

    @Column(name = "city")
    private String city;

    @Column(name = "address")
    private String address;

    @Column(name = "balance_amount")
    private Integer balanceAmount;

    @Column(name = "is_obsolete")
    private Boolean isObsolete;


    // Assuming vehicles relationship: one party can have many vehicles
    @OneToMany(mappedBy = "party", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JsonIgnoreProperties("party")
    private java.util.List<PartyVehicle> partyVehicles;
}
