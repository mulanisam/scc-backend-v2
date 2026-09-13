package com.app.entity;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class City {

	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;
    private boolean obsolete;

    @ManyToOne
    @JoinColumn(name = "route_id", nullable = false)
    @JsonIgnoreProperties("cities")
    private Route route;

    /**
     * The customers in this city.
     *
     * @JsonIgnore, not merely @JsonIgnoreProperties. Nothing asks a city for its customers -
     * the customers screen fetches /user/customers - and publishing them here cost twice:
     * GET /user/cities was 164 KB of customer records nobody read, and with
     * spring.jpa.open-in-view off it stopped working altogether, because a lazy collection
     * reached through Route.cities has no session left to load from.
     *
     * The association stays for the queries that navigate it in Java; only the JSON drops it.
     */
    @JsonIgnore
    @OneToMany(mappedBy = "city")
    private List<Customer> customers;
}
