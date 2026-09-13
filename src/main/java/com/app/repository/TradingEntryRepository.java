package com.app.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.entity.TradingEntry;

@Repository
public interface TradingEntryRepository extends JpaRepository<TradingEntry, Long> {

    /**
     * One party's entries over a period, newest first - the trading ledger's detail.
     *
     * Obsolete rows are included on purpose. A superseded entry is kept for trace, and a
     * ledger that hid it would not add up against the statement, which shows it greyed.
     */
    @Query("""
            SELECT e FROM TradingEntry e
             WHERE e.party.id = :partyId
               AND (:from IS NULL OR e.date >= :from)
               AND (:to   IS NULL OR e.date <= :to)
             ORDER BY e.date DESC, e.id DESC
            """)
    List<TradingEntry> findForParty(@Param("partyId") Long partyId,
                                   @Param("from") LocalDate from,
                                   @Param("to") LocalDate to,
                                   Limit limit);

    /** Every party's entries over a period, for the trading day book. */
    @Query("""
            SELECT e FROM TradingEntry e
             WHERE (:from IS NULL OR e.date >= :from)
               AND (:to   IS NULL OR e.date <= :to)
             ORDER BY e.date DESC, e.id DESC
            """)
    List<TradingEntry> findForPeriod(@Param("from") LocalDate from,
                                     @Param("to") LocalDate to,
                                     Limit limit);

    /**
     * Per-party totals for the report: how much was traded and how much came back.
     *
     * Aggregated in the database rather than by loading every entry and summing in Java -
     * route 9 alone brought 571 rows, and a year of trading will be several thousand.
     * Obsolete entries are excluded here, because a report is a statement of what was
     * actually traded and a superseded row would double-count it.
     */
    @Query("""
            SELECT e.party.id,
                   e.party.name,
                   COUNT(e),
                   COALESCE(SUM(e.birdsSold), 0),
                   COALESCE(SUM(e.kilograms), 0),
                   COALESCE(SUM(e.amount), 0),
                   COALESCE(SUM(e.payment), 0),
                   MIN(e.date),
                   MAX(e.date)
              FROM TradingEntry e
             WHERE e.obsolete = false
               AND (:from IS NULL OR e.date >= :from)
               AND (:to   IS NULL OR e.date <= :to)
             GROUP BY e.party.id, e.party.name
             ORDER BY COALESCE(SUM(e.amount), 0) DESC
            """)
    List<Object[]> summariseByParty(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** Day-by-day totals across all parties, for the trend on the report screen. */
    @Query("""
            SELECT e.date,
                   COUNT(e),
                   COALESCE(SUM(e.birdsSold), 0),
                   COALESCE(SUM(e.kilograms), 0),
                   COALESCE(SUM(e.amount), 0),
                   COALESCE(SUM(e.payment), 0)
              FROM TradingEntry e
             WHERE e.obsolete = false
               AND (:from IS NULL OR e.date >= :from)
               AND (:to   IS NULL OR e.date <= :to)
             GROUP BY e.date
             ORDER BY e.date DESC
            """)
    List<Object[]> summariseByDay(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
