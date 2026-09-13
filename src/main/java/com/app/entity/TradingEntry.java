package com.app.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "trading_entries")
public class TradingEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private LocalDate date;

    private Integer birds;
    private BigDecimal kilograms;
    private BigDecimal rate;
    private BigDecimal amount;
    private BigDecimal payment;
    private BigDecimal pending;
    private BigDecimal balanceAmount;
    private String description;

    /** Birds actually sold to this party, as against loaded on the trip. */
    private Integer birdsSold;

    private String paymentMode;

    /** Superseded by a correction. Kept for trace, never deleted. */
    @Column(nullable = false)
    private boolean obsolete = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "party_id", nullable = false)
    private Party party;

    /**
     * The ledger account this entry bills to.
     *
     * Trading needs one because everything that makes a balance answerable is keyed on
     * it: the running balance, the statement, the daily message, the credit check and the
     * payment reversal all read customer_ledger. Route 9's 44 lakh carried six months of
     * that history, and an entry with nowhere to post would have thrown it away.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "customer_id")
    private Customer customer;

    /** The load this entry came off. Null for an entry recorded on its own. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trip_id")
    private TradingTrip trip;

    /*
     * Nullable, and it has to be.
     *
     * It was NOT NULL, which the 571 converted entries cannot satisfy: they are sales to
     * a party and no supplier was ever recorded against them. A trading entry that buys
     * from a supplier and sells to a party still names one.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    /**
     * The vehicle that carried this load, as its registration number.
     *
     * Text rather than a foreign key. The entry records what happened on a date, and it
     * has to keep saying which vehicle came even after that vehicle stops being one of
     * the party's - the same reason the message outbox snapshots a phone number instead
     * of joining to the customer.
     */
    @Column(name = "vehicle_number", length = 40)
    private String vehicleNumber;
}
