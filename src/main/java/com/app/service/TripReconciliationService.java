package com.app.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.report.SalesReportRequest;
import com.app.dto.report.TripReconciliationResponse;
import com.app.dto.report.TripReconciliationRow;
import com.app.utility.MoneyRules;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * Trip-level reconciliation.
 *
 * For each vehicle load: birds out, what became of them, weight and money back,
 * what is still owed, and whether it all adds up.
 *
 * Two variances are reported separately because they mean different things:
 *
 *  - weightLoss is real shrinkage between the farm and the customer. It needs
 *    the loaded weight, which was never recorded before sale_details gained a
 *    column for it, so it is null for historical trips - unknown, not zero.
 *  - headerWeightVariance is the trip header disagreeing with its own sale
 *    lines. That is a data fault, not shrinkage. It was endemic before the
 *    weight column was widened to decimal: 1,833 of 2,228 trips did not tally.
 */
@Service
public class TripReconciliationService {

    private static final Logger logger = LoggerFactory.getLogger(TripReconciliationService.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public TripReconciliationResponse reconcile(SalesReportRequest request) {
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new IllegalArgumentException("Both a start date and an end date are required.");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("End date " + request.getEndDate()
                    + " is before start date " + request.getStartDate() + ".");
        }

        logger.info("Trip reconciliation {} to {} (route={}, driver={}, vehicle={})",
                request.getStartDate(), request.getEndDate(),
                request.getRouteId(), request.getDriverId(), request.getVehicleId());

        StringBuilder sql = new StringBuilder("""
                SELECT sd.id, sd.date, r.name, v.vehicle_no, d.name,
                       sd.total_birds, sd.mortality, sd.return_to_farm,
                       sd.loaded_kilograms, sd.total_kilogram_sale,
                       sd.is_correction, sd.correction_note,
                       COALESCE(sale_lines.birds_sold, 0)   AS birds_sold,
                       COALESCE(sale_lines.weight_sold, 0)  AS weight_sold,
                       COALESCE(sale_lines.amount, 0)       AS amount,
                       COALESCE(sale_lines.paid, 0)         AS paid,
                       COALESCE(sale_lines.pending, 0)      AS pending,
                       COALESCE(sale_lines.customers, 0)    AS customers,
                       COALESCE(sale_lines.line_count, 0)   AS line_count,
                       COALESCE(bal.closing_balance, 0) AS closing_balance
                FROM sale_details sd
                JOIN route r   ON r.id = sd.route_id
                JOIN driver d  ON d.id = sd.driver_id
                LEFT JOIN vehicle v ON v.id = sd.vehicle_id
                LEFT JOIN (
                    SELECT sale_details_id,
                           SUM(birds)                AS birds_sold,
                           SUM(kilograms)            AS weight_sold,
                           SUM(amount)               AS amount,
                           SUM(payment)              AS paid,
                           SUM(pending)              AS pending,
                           COUNT(DISTINCT customer_id) AS customers,
                           COUNT(*)                  AS line_count
                    FROM sale
                    WHERE sale_details_id IS NOT NULL
                    GROUP BY sale_details_id
                ) sale_lines ON sale_lines.sale_details_id = sd.id
                LEFT JOIN (
                    -- Closing balance of this trip's customers as at the trip
                    -- date, from the ledger rather than the stale running column
                    -- on sale.
                    SELECT s.sale_details_id, SUM(latest.running_balance) AS closing_balance
                    FROM (SELECT DISTINCT sale_details_id, customer_id FROM sale
                          WHERE sale_details_id IS NOT NULL) s
                    JOIN sale_details sd2 ON sd2.id = s.sale_details_id
                    JOIN (
                        SELECT cl.customer_id, cl.transaction_date, cl.running_balance,
                               ROW_NUMBER() OVER (PARTITION BY cl.customer_id
                                                  ORDER BY cl.transaction_date DESC, cl.id DESC) rn
                        FROM customer_ledger cl
                    ) latest ON latest.customer_id = s.customer_id AND latest.rn = 1
                    GROUP BY s.sale_details_id
                ) bal ON bal.sale_details_id = sd.id
                WHERE sd.date BETWEEN :startDate AND :endDate
                """);

        if (request.getRouteId() != null)   sql.append(" AND sd.route_id = :routeId ");
        if (request.getDriverId() != null)  sql.append(" AND sd.driver_id = :driverId ");
        if (request.getVehicleId() != null) sql.append(" AND sd.vehicle_id = :vehicleId ");
        sql.append(" ORDER BY sd.date, r.name, sd.id");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("startDate", request.getStartDate());
        query.setParameter("endDate", request.getEndDate());
        if (request.getRouteId() != null)   query.setParameter("routeId", request.getRouteId());
        if (request.getDriverId() != null)  query.setParameter("driverId", request.getDriverId());
        if (request.getVehicleId() != null) query.setParameter("vehicleId", request.getVehicleId());

        List<TripReconciliationRow> trips = new ArrayList<>();
        @SuppressWarnings("unchecked")
        List<Object[]> results = query.getResultList();

        for (Object[] r : results) {
            trips.add(buildRow(r));
        }

        return summarise(request, trips);
    }

    private TripReconciliationRow buildRow(Object[] r) {
        TripReconciliationRow row = new TripReconciliationRow();

        row.setTripId(toLong(r[0]));
        row.setDate(toLocalDate(r[1]));
        row.setRoute((String) r[2]);
        row.setVehicle((String) r[3]);
        row.setDriver((String) r[4]);

        row.setBirdsLoaded(toInteger(r[5]));
        row.setMortality(toInteger(r[6]));
        row.setReturnToFarm(toInteger(r[7]));

        BigDecimal loaded = r[8] == null ? null : toBigDecimal(r[8]);
        BigDecimal headerWeight = toBigDecimal(r[9]);

        row.setCorrection(toBoolean(r[10]));
        row.setCorrectionNote((String) r[11]);

        row.setBirdsSold(orZero(toLong(r[12])));
        BigDecimal weightSold = toBigDecimal(r[13]);
        row.setWeightSold(MoneyRules.weight(weightSold));
        row.setAmount(MoneyRules.money(toBigDecimal(r[14])));
        row.setPaid(MoneyRules.money(toBigDecimal(r[15])));
        row.setPending(MoneyRules.money(toBigDecimal(r[16])));
        row.setCustomerCount(orZero(toLong(r[17])) > 0 ? toLong(r[17]).intValue() : 0);
        row.setSaleLineCount(orZero(toLong(r[18])));
        row.setClosingBalance(MoneyRules.money(toBigDecimal(r[19])));

        // Birds: loaded must equal sold + dead + returned.
        MoneyRules.BirdReconciliation birds = MoneyRules.reconcileBirds(
                row.getBirdsLoaded(), (int) row.getBirdsSold(),
                row.getMortality(), row.getReturnToFarm());
        row.setBirdVariance(birds.getDifference());
        row.setBirdsBalanced(birds.isBalanced());

        // Real shrinkage, only where the loaded weight is known.
        row.setWeightLoaded(loaded == null ? null : MoneyRules.weight(loaded));
        if (loaded != null && loaded.signum() > 0) {
            BigDecimal loss = MoneyRules.weight(loaded.subtract(weightSold));
            row.setWeightLoss(loss);
            row.setWeightLossPercent(
                    loss.multiply(BigDecimal.valueOf(100)).divide(loaded, 2, RoundingMode.HALF_UP));
        }

        // Header against its own lines: a data fault, not shrinkage.
        row.setHeaderWeightVariance(MoneyRules.weight(headerWeight.subtract(weightSold)));

        if (row.getBirdsSold() > 0) {
            row.setAverageWeightPerBird(weightSold.divide(
                    BigDecimal.valueOf(row.getBirdsSold()), 3, RoundingMode.HALF_UP));
        }
        row.setAverageRate(rate(row.getAmount(), weightSold));

        return row;
    }

    private TripReconciliationResponse summarise(SalesReportRequest request,
            List<TripReconciliationRow> trips) {

        TripReconciliationResponse response = new TripReconciliationResponse();
        response.setStartDate(request.getStartDate());
        response.setEndDate(request.getEndDate());
        response.setTrips(trips);
        response.setTripCount(trips.size());

        List<String> filters = new ArrayList<>();
        if (request.getRouteId() != null)   filters.add("Route #" + request.getRouteId());
        if (request.getDriverId() != null)  filters.add("Driver #" + request.getDriverId());
        if (request.getVehicleId() != null) filters.add("Vehicle #" + request.getVehicleId());
        response.setAppliedFilters(filters);

        BigDecimal weightSold = BigDecimal.ZERO;
        BigDecimal weightLoaded = BigDecimal.ZERO;
        BigDecimal weightLoss = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;

        for (TripReconciliationRow trip : trips) {
            response.setBirdsLoaded(response.getBirdsLoaded() + orZero(trip.getBirdsLoaded()));
            response.setBirdsSold(response.getBirdsSold() + trip.getBirdsSold());
            response.setMortality(response.getMortality() + orZero(trip.getMortality()));
            response.setReturnToFarm(response.getReturnToFarm() + orZero(trip.getReturnToFarm()));

            weightSold = weightSold.add(trip.getWeightSold());
            amount = amount.add(trip.getAmount());
            paid = paid.add(trip.getPaid());
            pending = pending.add(trip.getPending());

            // Only trips with a known loaded weight contribute to shrinkage.
            if (trip.getWeightLoaded() != null) {
                weightLoaded = weightLoaded.add(trip.getWeightLoaded());
                weightLoss = weightLoss.add(
                        trip.getWeightLoss() == null ? BigDecimal.ZERO : trip.getWeightLoss());
                response.setTripsWithLoadedWeight(response.getTripsWithLoadedWeight() + 1);
            }

            if (!trip.isBirdsBalanced()) {
                response.setUnbalancedTripCount(response.getUnbalancedTripCount() + 1);
            }
            if (trip.getHeaderWeightVariance().abs().compareTo(new BigDecimal("0.0005")) > 0) {
                response.setHeaderMismatchCount(response.getHeaderMismatchCount() + 1);
            }
            if (trip.isCorrection()) {
                response.setCorrectionCount(response.getCorrectionCount() + 1);
            }
        }

        response.setBirdVariance(response.getBirdsLoaded()
                - (response.getBirdsSold() + response.getMortality() + response.getReturnToFarm()));

        response.setWeightSold(MoneyRules.weight(weightSold));
        response.setAmount(MoneyRules.money(amount));
        response.setPaid(MoneyRules.money(paid));
        response.setPending(MoneyRules.money(pending));
        response.setAverageRate(rate(response.getAmount(), weightSold));

        // Left null when no trip in the range recorded a loaded weight, so the
        // report shows "unknown" rather than a misleading zero loss.
        if (response.getTripsWithLoadedWeight() > 0) {
            response.setWeightLoaded(MoneyRules.weight(weightLoaded));
            response.setWeightLoss(MoneyRules.weight(weightLoss));
            if (weightLoaded.signum() > 0) {
                response.setWeightLossPercent(weightLoss.multiply(BigDecimal.valueOf(100))
                        .divide(weightLoaded, 2, RoundingMode.HALF_UP));
            }
        }

        long birdsHandled = response.getBirdsSold() + response.getMortality()
                + response.getReturnToFarm();
        if (birdsHandled > 0) {
            response.setMortalityPercent(BigDecimal.valueOf(response.getMortality())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(birdsHandled), 2, RoundingMode.HALF_UP));
        }

        return response;
    }

    private BigDecimal rate(BigDecimal amount, BigDecimal weight) {
        if (weight == null || weight.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return amount.divide(weight, 2, RoundingMode.HALF_UP);
    }

    private static long orZero(Long value) { return value == null ? 0 : value; }
    private static int orZero(Integer value) { return value == null ? 0 : value; }

    private static Long toLong(Object v) { return v == null ? null : ((Number) v).longValue(); }
    private static Integer toInteger(Object v) { return v == null ? null : ((Number) v).intValue(); }
    private static boolean toBoolean(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        return ((Number) v).intValue() != 0;
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal d) return d;
        return BigDecimal.valueOf(((Number) v).doubleValue());
    }

    private static LocalDate toLocalDate(Object v) {
        if (v == null) return null;
        if (v instanceof Date d) return d.toLocalDate();
        if (v instanceof LocalDate d) return d;
        return LocalDate.parse(v.toString().substring(0, 10));
    }
}
