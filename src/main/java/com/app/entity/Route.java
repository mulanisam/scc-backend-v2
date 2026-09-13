package com.app.entity;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Route {

	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    /**
     * The cities on this route.
     *
     * EAGER because GET /user/routes serialises them and @OneToMany is lazy by default -
     * with spring.jpa.open-in-view off there is no session left when Jackson reaches this.
     * Nine routes and 171 cities in total, so the cost is nothing; the graph is kept out of
     * a sale payload by @JsonIgnoreProperties on Sale.route rather than by laziness, which
     * was never a reliable way to control it.
     */
    @OneToMany(mappedBy = "route", fetch = FetchType.EAGER)
    @JsonIgnoreProperties("route")
    private List<City> cities;
    private boolean obsolete;

//    @OneToMany(mappedBy = "route")
//    private List<Customer> customers;

}
