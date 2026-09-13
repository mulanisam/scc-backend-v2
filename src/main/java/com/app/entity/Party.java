package com.app.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
    private BigDecimal balanceAmount;

    @Column(name = "is_obsolete")
    private Boolean isObsolete;

    @Column(name = "mobile_no", length = 20)
    private String mobileNo;

    /**
     * The customer record this party is billed through.
     *
     * A party is an operational counterparty; the balance, the statement and the daily
     * message all live on a customer and its ledger. Rather than duplicate six months of
     * running balances into trading, a party points at the account that already holds
     * them - which is how route 9's 44 lakh survived being moved into Trading.
     *
     * Unique, so two parties cannot bill to one account.
     *
     * @JsonIgnore for two reasons, one of which would have been an outage. Four endpoints
     * return Party directly, and with spring.jpa.open-in-view now off there is no session
     * open when Jackson reaches this - serialising the uninitialised proxy fails the
     * request, which is exactly how the trading entry response broke before it was given a
     * view. The second reason stands on its own: a party is not the place to publish a
     * customer's balance, credit limit and messaging consent, and TradingPartyRow already
     * carries the customerId for anything that needs the link.
     */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id", unique = true)
    private Customer customer;

    /**
     * The registration numbers of the vehicles this party uses.
     *
     * An element collection, not an entity. A party vehicle has one attribute and no
     * life of its own - nothing refers to one, nobody looks one up, and there is nothing
     * to record against it - so the PartyVehicle entity, its repository, its six REST
     * endpoints and its Master Data tab existed to hold a registration number against a
     * party. One of those endpoints could not even be used: it bound the entity, so the
     * screen's bare id was rejected as a 400 every time.
     *
     * EAGER because it is always wanted with the party and there are at most a handful:
     * the list is rendered wherever a party is, and lazily loading it outside a
     * transaction was the other way this could break.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "party_vehicle_numbers",
            joinColumns = @JoinColumn(name = "party_id"))
    @Column(name = "vehicle_number", length = 40, nullable = false)
    private List<String> vehicleNumbers = new ArrayList<>();
}
