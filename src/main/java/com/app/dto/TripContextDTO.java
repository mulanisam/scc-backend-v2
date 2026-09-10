package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the sale entry screen needs to know before it submits, for a given date
 * and route.
 *
 * It answers two questions the client cannot answer on its own:
 * whether a trip is already recorded for that date and route (a possible
 * duplicate), and when the route last had a sale (so an earlier date can be
 * confirmed as a backdated entry rather than saved silently).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TripContextDTO {

    /** The date and route the client asked about. */
    private LocalDate date;
    private Long routeId;

    /** True when at least one trip already exists for that date and route. */
    private boolean duplicate;

    /** How many trips exist, and what they already hold. */
    private int existingTripCount;
    private Integer existingBirds;
    private BigDecimal existingAmount;

    /**
     * Date of the most recent trip on this route, or null if it has none.
     * The client compares its own date against this to decide whether to ask
     * for backdate confirmation.
     */
    private LocalDate lastSaleDate;

    /** Positive when the requested date falls before lastSaleDate. */
    private Integer daysBeforeLastSale;
}
