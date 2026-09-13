package com.app.dto.trading;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * What the wholesale side did over a period.
 *
 * Separate from the sales reports on purpose. Trading and the routes are different
 * businesses measured differently - a route report is read per trip and per driver, a
 * trading report per party - and route 9 spent six months inside the route reports
 * distorting exactly those figures. Keeping them apart is the point of the move.
 */
public record TradingReport(
        LocalDate from,
        LocalDate to,
        Totals totals,
        List<PartyLine> parties,
        List<DayLine> days) {

    /**
     * @param outstanding what the parties owe altogether, read from their ledger
     *        accounts rather than derived from amount minus received - a party can be
     *        carrying a balance from before this period, and 44,03,490 of route 9's was.
     */
    public record Totals(
            long entries,
            int parties,
            long birds,
            BigDecimal kilograms,
            BigDecimal amount,
            BigDecimal received,
            BigDecimal outstanding,
            /** Amount ÷ weight over the whole period. Zero-safe. */
            BigDecimal averageRate) {
    }

    public record PartyLine(
            Long partyId,
            String name,
            long entries,
            long birds,
            BigDecimal kilograms,
            BigDecimal amount,
            BigDecimal received,
            BigDecimal averageRate,
            /** The party's ledger balance now, not the period's movement. */
            BigDecimal balance,
            LocalDate firstEntry,
            LocalDate lastEntry) {
    }

    public record DayLine(
            LocalDate date,
            long entries,
            long birds,
            BigDecimal kilograms,
            BigDecimal amount,
            BigDecimal received,
            BigDecimal averageRate) {
    }
}
