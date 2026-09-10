package com.app.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.app.dto.dashboard.DashboardCustomerBalanceRow;
import com.app.dto.dashboard.DashboardExceptions;
import com.app.dto.dashboard.DashboardPurchaseTotals;
import com.app.dto.dashboard.DashboardRouteRow;
import com.app.dto.dashboard.DashboardSalesTotals;
import com.app.dto.dashboard.DashboardSupplierRow;
import com.app.dto.dashboard.DashboardTrendPoint;
import com.app.utility.MoneyRules;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Dashboard queries.
 *
 * Native SQL, one query per panel, every date bound as a parameter. Sale money and
 * quantities come from the sale rows and trip-level figures from sale_details,
 * because only the trip records mortality, return to farm and loaded weight -
 * summing those from the sale rows would be summing columns that do not exist
 * there. The two are never added together into one figure.
 */
@Repository
public class DashboardQueryRepository {

    /** "09 Sep" and "Wed" - built here so the screen does not re-derive them. */
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd MMM");
    private static final DateTimeFormatter WEEKDAY_LABEL = DateTimeFormatter.ofPattern("EEE");

    @PersistenceContext
    private EntityManager entityManager;

    // ---- sales -----------------------------------------------------------

    /**
     * Sales aggregate for a date range, with the trip-level figures attached from
     * a second aggregate over the same range.
     */
    public DashboardSalesTotals salesTotals(String label, LocalDate from, LocalDate to) {
        Object[] sale = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*),
                       COUNT(DISTINCT s.customer_id),
                       COALESCE(SUM(s.birds), 0),
                       COALESCE(SUM(s.kilograms), 0),
                       COALESCE(SUM(s.amount), 0),
                       COALESCE(SUM(s.payment), 0),
                       COALESCE(SUM(s.pending), 0)
                FROM sale s
                WHERE s.obsolete = 0 AND s.date BETWEEN :from AND :to
                """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();

        Object[] trip = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*),
                       COALESCE(SUM(sd.total_birds), 0),
                       COALESCE(SUM(sd.mortality), 0),
                       COALESCE(SUM(sd.return_to_farm), 0),
                       COALESCE(SUM(sd.loaded_kilograms), 0)
                FROM sale_details sd
                WHERE sd.date BETWEEN :from AND :to
                """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();

        DashboardSalesTotals totals = new DashboardSalesTotals();
        totals.setLabel(label);
        totals.setSaleCount(asLong(sale[0]));
        totals.setCustomerCount(asLong(sale[1]));
        totals.setBirdsSold(asLong(sale[2]));
        totals.setWeightSold(MoneyRules.weight(asDecimal(sale[3])));
        totals.setAmount(MoneyRules.money(asDecimal(sale[4])));
        totals.setReceived(MoneyRules.money(asDecimal(sale[5])));
        totals.setPending(MoneyRules.money(asDecimal(sale[6])));

        totals.setTripCount(asLong(trip[0]));
        totals.setBirdsLoaded(asLong(trip[1]));
        totals.setMortality(asLong(trip[2]));
        totals.setReturnToFarm(asLong(trip[3]));
        totals.setWeightLoaded(MoneyRules.weight(asDecimal(trip[4])));

        totals.setAverageRate(perUnit(totals.getAmount(), totals.getWeightSold()));
        totals.setRecoveryPercent(percentOf(totals.getReceived(), totals.getAmount()));
        totals.setWeightGap(totals.getWeightLoaded().signum() == 0
                ? BigDecimal.ZERO.setScale(3)
                : MoneyRules.weight(totals.getWeightLoaded().subtract(totals.getWeightSold())));
        return totals;
    }

    /**
     * One row per day across the range, including days with no trading.
     *
     * The money and weight come from the sale rows and the bird movement from the
     * trip records, joined per day, so mortality, return to farm and the tally can
     * be shown beside what was billed. A day can have a trip with no sale rows or
     * sale rows with no trip, so the two sides are aggregated separately and
     * merged by date rather than joined in one query, which would drop such a day
     * entirely or multiply the sums.
     */
    public List<DashboardTrendPoint> dailyTrend(LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Object[]> saleRows = entityManager.createNativeQuery("""
                SELECT s.date,
                       COUNT(DISTINCT s.sale_details_id),
                       COALESCE(SUM(s.birds), 0),
                       COALESCE(SUM(s.kilograms), 0),
                       COALESCE(SUM(s.amount), 0),
                       COALESCE(SUM(s.payment), 0),
                       COALESCE(SUM(s.pending), 0)
                FROM sale s
                WHERE s.obsolete = 0 AND s.date BETWEEN :from AND :to
                GROUP BY s.date
                ORDER BY s.date
                """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        @SuppressWarnings("unchecked")
        List<Object[]> tripRows = entityManager.createNativeQuery("""
                SELECT sd.date,
                       COUNT(*),
                       COALESCE(SUM(sd.total_birds), 0),
                       COALESCE(SUM(sd.total_bird_sale), 0),
                       COALESCE(SUM(sd.mortality), 0),
                       COALESCE(SUM(sd.return_to_farm), 0)
                FROM sale_details sd
                WHERE sd.date BETWEEN :from AND :to
                GROUP BY sd.date
                ORDER BY sd.date
                """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        Map<LocalDate, Object[]> salesByDate = new HashMap<>();
        for (Object[] row : saleRows) {
            salesByDate.put(asLocalDate(row[0]), row);
        }
        Map<LocalDate, Object[]> tripsByDate = new HashMap<>();
        for (Object[] row : tripRows) {
            tripsByDate.put(asLocalDate(row[0]), row);
        }

        List<DashboardTrendPoint> points = new ArrayList<>();
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            DashboardTrendPoint point = new DashboardTrendPoint();
            point.setDate(date);
            point.setLabel(date.format(DAY_LABEL));
            point.setWeekday(date.format(WEEKDAY_LABEL));

            Object[] sale = salesByDate.get(date);
            if (sale != null) {
                point.setTraded(true);
                point.setTripCount(asLong(sale[1]));
                point.setBirdsSold(asLong(sale[2]));
                point.setWeight(MoneyRules.weight(asDecimal(sale[3])));
                point.setAmount(MoneyRules.money(asDecimal(sale[4])));
                point.setReceived(MoneyRules.money(asDecimal(sale[5])));
                point.setPending(MoneyRules.money(asDecimal(sale[6])));
            } else {
                point.setWeight(BigDecimal.ZERO.setScale(3));
                point.setAmount(BigDecimal.ZERO.setScale(2));
                point.setReceived(BigDecimal.ZERO.setScale(2));
                point.setPending(BigDecimal.ZERO.setScale(2));
            }

            Object[] trip = tripsByDate.get(date);
            if (trip != null) {
                point.setTraded(true);
                // The trip count from the trip table is the authoritative one when
                // it is present; sale rows only reveal trips that have sales.
                point.setTripCount(Math.max(point.getTripCount(), asLong(trip[1])));
                point.setBirdsLoaded(asLong(trip[2]));
                point.setMortality(asLong(trip[4]));
                point.setReturnToFarm(asLong(trip[5]));
                point.setBirdsSoldOnTripRecord(asLong(trip[3]));

                // Deliberately the sale rows' figure, not the trip header's: the
                // sale rows are what was billed, and using them makes the row's own
                // arithmetic add up on screen. Where the header disagrees with its
                // rows - it does on several days here - that is reported separately
                // rather than silently changing what the tally means.
                point.setBirdTally(point.getBirdsLoaded() - point.getBirdsSold()
                        - point.getMortality() - point.getReturnToFarm());
                point.setTripRecordAgrees(point.getBirdsSoldOnTripRecord() == point.getBirdsSold());
            } else {
                point.setTripRecordAgrees(true);
            }
            // A day with no trip record has nothing to tally, so it is not a
            // mismatch - the table shows a dash there rather than a false zero.
            point.setTallies(trip == null || point.getBirdTally() == 0);

            points.add(point);
        }
        return points;
    }

    /**
     * Route-wise trading for a range, biggest by amount first.
     *
     * Outstanding is the ledger balance of the customers who traded on the route
     * within the range. A route has no balance of its own - its customers do - so
     * the same customer trading on two routes contributes to both, and the column
     * is a view of exposure by route rather than a partition of the total.
     */
    public List<DashboardRouteRow> routeBreakdown(LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT r.id,
                       r.name,
                       COUNT(DISTINCT s.sale_details_id),
                       COUNT(DISTINCT s.customer_id),
                       COALESCE(SUM(s.birds), 0),
                       COALESCE(SUM(s.kilograms), 0),
                       COALESCE(SUM(s.amount), 0),
                       COALESCE(SUM(s.payment), 0),
                       COALESCE(SUM(s.pending), 0),
                       COALESCE((
                           SELECT SUM(c2.balance_amount)
                           FROM customer c2
                           WHERE c2.id IN (
                               SELECT DISTINCT s2.customer_id
                               FROM sale s2
                               WHERE s2.route_id = r.id
                                 AND s2.obsolete = 0
                                 AND s2.date BETWEEN :from AND :to
                           )
                       ), 0)
                FROM sale s
                JOIN route r ON r.id = s.route_id
                WHERE s.obsolete = 0 AND s.date BETWEEN :from AND :to
                GROUP BY r.id, r.name
                ORDER BY SUM(s.amount) DESC
                """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        BigDecimal total = BigDecimal.ZERO;
        for (Object[] row : rows) {
            total = total.add(asDecimal(row[6]));
        }

        List<DashboardRouteRow> result = new ArrayList<>();
        for (Object[] row : rows) {
            DashboardRouteRow route = new DashboardRouteRow();
            route.setRouteId(asLong(row[0]));
            route.setRouteName((String) row[1]);
            route.setTripCount(asLong(row[2]));
            route.setCustomerCount(asLong(row[3]));
            route.setBirds(asLong(row[4]));
            route.setWeight(MoneyRules.weight(asDecimal(row[5])));
            route.setAmount(MoneyRules.money(asDecimal(row[6])));
            route.setReceived(MoneyRules.money(asDecimal(row[7])));
            route.setPending(MoneyRules.money(asDecimal(row[8])));
            route.setOutstanding(MoneyRules.money(asDecimal(row[9])));
            route.setAverageRate(perUnit(route.getAmount(), route.getWeight()));
            route.setSharePercent(percentOf(route.getAmount(), total));
            result.add(route);
        }
        return result;
    }

    /**
     * Latest date up to today that has a sale on it, or null when there are none.
     *
     * Future-dated sales are excluded deliberately: this database holds two dated
     * up to 2026-12-10, and reporting one of those as "last traded" would tell
     * somebody looking at an empty day that trading is up to date when it is not.
     * Those sales are counted separately as an exception.
     */
    public LocalDate latestTradingDate() {
        Object result = entityManager
                .createNativeQuery("SELECT MAX(s.date) FROM sale s WHERE s.obsolete = 0 AND s.date <= CURDATE()")
                .getSingleResult();
        return result == null ? null : asLocalDate(result);
    }

    // ---- purchases -------------------------------------------------------

    public DashboardPurchaseTotals purchaseTotals(String label, LocalDate from, LocalDate to) {
        // The quantities live on dc_detail, one or more lines per purchase, so the
        // header figures are collapsed first to keep them from being multiplied by
        // the line count.
        Object[] row = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*),
                       COALESCE(SUM(h.total_amount), 0),
                       COALESCE(SUM(h.paid_amount), 0),
                       COALESCE(SUM(h.expenses), 0),
                       COALESCE(SUM(h.birds), 0),
                       COALESCE(SUM(h.weight), 0)
                FROM (
                    SELECT p.id,
                           p.total_amount,
                           p.paid_amount,
                           COALESCE(p.diesel, 0) + COALESCE(p.driver_expense, 0)
                               + COALESCE(p.hamali, 0) AS expenses,
                           (SELECT COALESCE(SUM(dc.nos), 0) FROM dc_detail dc
                             WHERE dc.purchase_id = p.id) AS birds,
                           (SELECT COALESCE(SUM(dc.kilograms), 0) FROM dc_detail dc
                             WHERE dc.purchase_id = p.id) AS weight
                    FROM purchase p
                    WHERE p.entry_date BETWEEN :from AND :to
                ) h
                """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getSingleResult();

        DashboardPurchaseTotals totals = new DashboardPurchaseTotals();
        totals.setLabel(label);
        totals.setPurchaseCount(asLong(row[0]));
        totals.setAmount(MoneyRules.money(asDecimal(row[1])));
        totals.setPaid(MoneyRules.money(asDecimal(row[2])));
        totals.setExpenses(MoneyRules.money(asDecimal(row[3])));
        totals.setBirdsBought(asLong(row[4]));
        totals.setWeightBought(MoneyRules.weight(asDecimal(row[5])));
        totals.setOwed(MoneyRules.money(totals.getAmount().subtract(totals.getPaid())));
        totals.setAverageRate(perUnit(totals.getAmount(), totals.getWeightBought()));
        return totals;
    }

    public List<DashboardSupplierRow> supplierBreakdown(LocalDate from, LocalDate to) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT sup.id,
                       sup.name,
                       COUNT(*),
                       COALESCE(SUM(h.birds), 0),
                       COALESCE(SUM(h.weight), 0),
                       COALESCE(SUM(h.total_amount), 0),
                       COALESCE(SUM(h.paid_amount), 0),
                       MAX(h.entry_date)
                FROM (
                    SELECT p.id, p.supplier_id, p.entry_date, p.total_amount, p.paid_amount,
                           (SELECT COALESCE(SUM(dc.nos), 0) FROM dc_detail dc
                             WHERE dc.purchase_id = p.id) AS birds,
                           (SELECT COALESCE(SUM(dc.kilograms), 0) FROM dc_detail dc
                             WHERE dc.purchase_id = p.id) AS weight
                    FROM purchase p
                    WHERE p.entry_date BETWEEN :from AND :to
                ) h
                JOIN supplier sup ON sup.id = h.supplier_id
                GROUP BY sup.id, sup.name
                ORDER BY SUM(h.total_amount) DESC
                """)
                .setParameter("from", from)
                .setParameter("to", to)
                .getResultList();

        List<DashboardSupplierRow> result = new ArrayList<>();
        for (Object[] row : rows) {
            DashboardSupplierRow supplier = new DashboardSupplierRow();
            supplier.setSupplierId(asLong(row[0]));
            supplier.setSupplierName((String) row[1]);
            supplier.setPurchaseCount(asLong(row[2]));
            supplier.setBirds(asLong(row[3]));
            supplier.setWeight(MoneyRules.weight(asDecimal(row[4])));
            supplier.setAmount(MoneyRules.money(asDecimal(row[5])));
            supplier.setPaid(MoneyRules.money(asDecimal(row[6])));
            supplier.setOwed(MoneyRules.money(supplier.getAmount().subtract(supplier.getPaid())));
            supplier.setAverageRate(perUnit(supplier.getAmount(), supplier.getWeight()));
            supplier.setLastPurchaseDate(row[7] == null ? null : asLocalDate(row[7]));
            result.add(supplier);
        }
        return result;
    }

    // ---- receivables -----------------------------------------------------

    /**
     * Customers who owe the most, with the date they last paid anything.
     *
     * A payment is any ledger credit, which includes collection taken on the sale
     * row itself - in this business most of it is, so looking only at PAYMENT rows
     * would report customers who pay on every trip as never having paid.
     */
    public List<DashboardCustomerBalanceRow> topDebtors(int limit) {
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT c.id, c.name, c.shop_name, city.name,
                       c.balance_amount, c.credit_limit, c.credit_limit_enabled,
                       (SELECT MAX(cl.transaction_date) FROM customer_ledger cl
                         WHERE cl.customer_id = c.id AND cl.credit_amount > 0),
                       (SELECT MAX(cl.transaction_date) FROM customer_ledger cl
                         WHERE cl.customer_id = c.id AND cl.debit_amount > 0)
                FROM customer c
                LEFT JOIN city city ON city.id = c.city_id
                WHERE c.balance_amount > 0
                ORDER BY c.balance_amount DESC
                LIMIT :limit
                """)
                .setParameter("limit", limit)
                .getResultList();

        List<DashboardCustomerBalanceRow> result = new ArrayList<>();
        LocalDate today = LocalDate.now();
        for (Object[] row : rows) {
            DashboardCustomerBalanceRow customer = new DashboardCustomerBalanceRow();
            customer.setCustomerId(asLong(row[0]));
            customer.setCustomerName((String) row[1]);
            customer.setShopName((String) row[2]);
            customer.setCityName((String) row[3]);
            customer.setBalance(MoneyRules.money(asDecimal(row[4])));
            customer.setCreditLimit(row[5] == null ? null : MoneyRules.money(asDecimal(row[5])));

            boolean limitEnabled = asBoolean(row[6]);
            customer.setOverCreditLimit(limitEnabled
                    && customer.getCreditLimit() != null
                    && customer.getCreditLimit().signum() > 0
                    && customer.getBalance().compareTo(customer.getCreditLimit()) > 0);

            if (row[7] != null) {
                LocalDate lastPayment = asLocalDate(row[7]);
                customer.setLastPaymentDate(lastPayment);
                customer.setDaysSinceLastPayment(java.time.temporal.ChronoUnit.DAYS.between(lastPayment, today));
            }
            if (row[8] != null) {
                customer.setLastSaleDate(asLocalDate(row[8]));
            }
            result.add(customer);
        }
        return result;
    }

    /** Balance-band counts and totals over the whole customer book. */
    public Object[] receivableBands() {
        return (Object[]) entityManager.createNativeQuery("""
                SELECT COALESCE(SUM(CASE WHEN c.balance_amount > 0 THEN c.balance_amount ELSE 0 END), 0),
                       SUM(CASE WHEN c.balance_amount > 0 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN c.balance_amount < 0 THEN 1 ELSE 0 END),
                       SUM(CASE WHEN c.balance_amount > 50000 THEN 1 ELSE 0 END),
                       COALESCE(SUM(CASE WHEN c.balance_amount > 50000 THEN c.balance_amount ELSE 0 END), 0),
                       SUM(CASE WHEN c.balance_amount > 20000 THEN 1 ELSE 0 END),
                       COALESCE(SUM(CASE WHEN c.balance_amount > 20000 THEN c.balance_amount ELSE 0 END), 0),
                       SUM(CASE WHEN c.credit_limit_enabled = 1 AND c.credit_limit > 0
                                     AND c.balance_amount > c.credit_limit THEN 1 ELSE 0 END),
                       COALESCE(SUM(CASE WHEN c.credit_limit_enabled = 1 AND c.credit_limit > 0
                                     AND c.balance_amount > c.credit_limit
                                THEN c.balance_amount - c.credit_limit ELSE 0 END), 0)
                FROM customer c
                """)
                .getSingleResult();
    }

    /**
     * Customers owing money who have not paid anything for the given number of
     * days, counting a customer who has never paid as stale.
     */
    public Object[] staleReceivables(int days) {
        return (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(c.balance_amount), 0)
                FROM customer c
                WHERE c.balance_amount > 0
                  AND COALESCE((SELECT MAX(cl.transaction_date) FROM customer_ledger cl
                                 WHERE cl.customer_id = c.id AND cl.credit_amount > 0),
                               '1900-01-01') < :cutoff
                """)
                .setParameter("cutoff", LocalDate.now().minusDays(days))
                .getSingleResult();
    }

    // ---- exceptions ------------------------------------------------------

    public DashboardExceptions exceptions() {
        DashboardExceptions exceptions = new DashboardExceptions();

        Object[] future = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(s.amount), 0)
                FROM sale s
                WHERE s.obsolete = 0 AND s.date > CURDATE()
                """).getSingleResult();
        exceptions.setFutureDatedSales(asLong(future[0]));
        exceptions.setFutureDatedAmount(MoneyRules.money(asDecimal(future[1])));

        Object[] birds = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(ABS(gap)), 0)
                FROM (
                    SELECT sd.total_birds
                             - COALESCE(sd.total_bird_sale, 0)
                             - COALESCE(sd.mortality, 0)
                             - COALESCE(sd.return_to_farm, 0) AS gap
                    FROM sale_details sd
                    WHERE sd.total_birds > 0
                ) t
                WHERE gap <> 0
                """).getSingleResult();
        exceptions.setTripsWithBirdMismatch(asLong(birds[0]));
        exceptions.setBirdMismatchTotal(asLong(birds[1]));

        // The trip header's stored sold count against the sale rows actually
        // attached to it. Nothing recalculates the header when a sale is edited.
        Object[] headerVersusRows = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(ABS(t.difference)), 0)
                FROM (
                    SELECT sd.id,
                           COALESCE(sd.total_bird_sale, 0)
                               - COALESCE((SELECT SUM(s.birds) FROM sale s
                                            WHERE s.sale_details_id = sd.id AND s.obsolete = 0), 0)
                               AS difference
                    FROM sale_details sd
                ) t
                WHERE t.difference <> 0
                """).getSingleResult();
        exceptions.setTripsWhereHeaderDisagreesWithRows(asLong(headerVersusRows[0]));
        exceptions.setHeaderVersusRowsBirdDifference(asLong(headerVersusRows[1]));

        Object noLoadedWeight = entityManager.createNativeQuery("""
                SELECT COUNT(*) FROM sale_details sd
                WHERE sd.loaded_kilograms IS NULL OR sd.loaded_kilograms = 0
                """).getSingleResult();
        exceptions.setTripsWithoutLoadedWeight(asLong(noLoadedWeight));

        Object[] weightGap = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(sd.loaded_kilograms - sd.total_kilogram_sale), 0)
                FROM sale_details sd
                WHERE sd.loaded_kilograms > 0
                  AND sd.total_kilogram_sale > 0
                  AND sd.loaded_kilograms - sd.total_kilogram_sale > sd.loaded_kilograms * 0.02
                """).getSingleResult();
        exceptions.setTripsWithWeightGap(asLong(weightGap[0]));
        exceptions.setWeightGapTotal(MoneyRules.weight(asDecimal(weightGap[1])));

        Object corrections = entityManager
                .createNativeQuery("SELECT COUNT(*) FROM sale_details sd WHERE sd.is_correction = 1")
                .getSingleResult();
        exceptions.setCorrectionTrips(asLong(corrections));

        Object[] unpaid = (Object[]) entityManager.createNativeQuery("""
                SELECT COUNT(*), COALESCE(SUM(p.total_amount), 0)
                FROM purchase p
                WHERE COALESCE(p.paid_amount, 0) = 0 AND COALESCE(p.total_amount, 0) > 0
                """).getSingleResult();
        exceptions.setUnpaidPurchases(asLong(unpaid[0]));
        exceptions.setUnpaidPurchaseAmount(MoneyRules.money(asDecimal(unpaid[1])));

        return exceptions;
    }

    // ---- conversions -----------------------------------------------------
    //
    // Native queries hand back whatever the driver chose: BigInteger or Long for
    // counts, BigDecimal or Double for sums, java.sql.Date for dates. Every read
    // goes through these so a driver change cannot turn into a ClassCastException
    // at runtime.

    private static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(value.toString());
    }

    private static boolean asBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean flag) return flag;
        return ((Number) value).intValue() != 0;
    }

    private static LocalDate asLocalDate(Object value) {
        if (value instanceof Date sqlDate) return sqlDate.toLocalDate();
        if (value instanceof LocalDate localDate) return localDate;
        return LocalDate.parse(value.toString());
    }

    /** Rate per unit; a zero denominator yields zero rather than an error. */
    private static BigDecimal perUnit(BigDecimal amount, BigDecimal quantity) {
        if (quantity == null || quantity.signum() == 0) return BigDecimal.ZERO.setScale(2);
        return amount.divide(quantity, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal percentOf(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) return BigDecimal.ZERO.setScale(1);
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 1, RoundingMode.HALF_UP);
    }
}
