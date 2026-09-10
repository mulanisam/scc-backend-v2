package com.app.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.report.CombinedSummaryResponse;
import com.app.dto.report.CombinedSummaryRow;
import com.app.dto.report.ReportPeriod;
import com.app.dto.report.SalesReportRequest;
import com.app.utility.MoneyRules;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * Bought against sold, per period.
 *
 * Each side is aggregated separately and then paired on the period bucket,
 * rather than joined in SQL: nothing links a purchase to the sale trips that
 * disposed of it, so a join would either multiply rows or silently drop the side
 * with no match.
 *
 * The comparison is only as good as the data behind it. This database holds 9
 * purchases against 56,099 sales, so the margin figures would read as a 28-crore
 * profit if presented without qualification. The response therefore reports
 * coverage - how many periods have both sides, and the sold-to-bought ratio -
 * and marks each period as comparable or not.
 */
@Service
public class CombinedReportService {

    private static final Logger logger = LoggerFactory.getLogger(CombinedReportService.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional(readOnly = true)
    public CombinedSummaryResponse compare(SalesReportRequest request) {
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new IllegalArgumentException("Both a start date and an end date are required.");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("End date " + request.getEndDate()
                    + " is before start date " + request.getStartDate() + ".");
        }

        ReportPeriod period = request.getPeriod();
        logger.info("Bought vs sold {} to {} period={}",
                request.getStartDate(), request.getEndDate(), period);

        Map<LocalDate, CombinedSummaryRow> byPeriod = new LinkedHashMap<>();
        loadPurchases(request, period, byPeriod);
        loadSales(request, period, byPeriod);

        List<CombinedSummaryRow> rows = new ArrayList<>(byPeriod.values());
        rows.sort((a, b) -> a.getPeriodStart().compareTo(b.getPeriodStart()));
        rows.forEach(row -> derive(row, period, request));

        return summarise(request, rows, period);
    }

    /** Purchase side: birds, weight and money bought, plus trip expenses. */
    private void loadPurchases(SalesReportRequest request, ReportPeriod period,
            Map<LocalDate, CombinedSummaryRow> byPeriod) {

        String bucket = period.bucketExpression("p.entry_date");

        String sql = """
                SELECT %s AS period_start,
                       COUNT(DISTINCT p.id)                        AS purchase_count,
                       COALESCE(SUM(dc.nos), 0)                    AS birds,
                       COALESCE(SUM(dc.kilograms), 0)              AS weight,
                       COALESCE(SUM(dc.amount), 0)                 AS amount,
                       COALESCE(SUM(expense.total), 0)             AS expenses,
                       COALESCE(SUM(expense.paid), 0)              AS paid
                FROM purchase p
                LEFT JOIN dc_detail dc ON dc.purchase_id = p.id
                LEFT JOIN (
                    -- Per purchase, so the header figures are not multiplied by
                    -- the number of DC lines joined above.
                    SELECT id,
                           COALESCE(diesel,0) + COALESCE(driver_expense,0)
                               + COALESCE(hamali,0) AS total,
                           COALESCE(paid_amount,0)  AS paid
                    FROM purchase
                ) expense ON expense.id = p.id
                WHERE p.entry_date BETWEEN :startDate AND :endDate
                GROUP BY period_start
                """.formatted(bucket);

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("startDate", request.getStartDate());
        query.setParameter("endDate", request.getEndDate());

        for (Object[] r : rows(query)) {
            CombinedSummaryRow row = rowFor(byPeriod, toLocalDate(r[0]));
            row.setPurchaseCount(toLong(r[1]));
            row.setBirdsBought(toLong(r[2]));
            row.setWeightBought(MoneyRules.weight(toBigDecimal(r[3])));
            row.setAmountBought(MoneyRules.money(toBigDecimal(r[4])));
            row.setPurchaseExpenses(MoneyRules.money(toBigDecimal(r[5])));
            row.setPaidToSuppliers(MoneyRules.money(toBigDecimal(r[6])));
        }
    }

    /** Sale side: birds, weight, money sold, recovered and pending. */
    private void loadSales(SalesReportRequest request, ReportPeriod period,
            Map<LocalDate, CombinedSummaryRow> byPeriod) {

        String bucket = period.bucketExpression("s.date");

        StringBuilder sql = new StringBuilder("""
                SELECT %s AS period_start,
                       COUNT(*)                      AS sale_count,
                       COALESCE(SUM(s.birds), 0)     AS birds,
                       COALESCE(SUM(s.kilograms), 0) AS weight,
                       COALESCE(SUM(s.amount), 0)    AS amount,
                       COALESCE(SUM(s.payment), 0)   AS payment,
                       COALESCE(SUM(s.pending), 0)   AS pending
                FROM sale s
                WHERE s.date BETWEEN :startDate AND :endDate
                """.formatted(bucket));

        if (request.getRouteId() != null)  sql.append(" AND s.route_id = :routeId ");
        if (request.getDriverId() != null) sql.append(" AND s.driver_id = :driverId ");
        sql.append(" GROUP BY period_start");

        Query query = entityManager.createNativeQuery(sql.toString());
        query.setParameter("startDate", request.getStartDate());
        query.setParameter("endDate", request.getEndDate());
        if (request.getRouteId() != null)  query.setParameter("routeId", request.getRouteId());
        if (request.getDriverId() != null) query.setParameter("driverId", request.getDriverId());

        for (Object[] r : rows(query)) {
            CombinedSummaryRow row = rowFor(byPeriod, toLocalDate(r[0]));
            row.setSaleCount(toLong(r[1]));
            row.setBirdsSold(toLong(r[2]));
            row.setWeightSold(MoneyRules.weight(toBigDecimal(r[3])));
            row.setAmountSold(MoneyRules.money(toBigDecimal(r[4])));
            row.setRecoveredFromCustomers(MoneyRules.money(toBigDecimal(r[5])));
            row.setPendingFromCustomers(MoneyRules.money(toBigDecimal(r[6])));
        }
    }

    /** Fills in every figure derived from the two sides of one period. */
    private void derive(CombinedSummaryRow row, ReportPeriod period, SalesReportRequest request) {
        zeroNulls(row);

        row.setPeriodEnd(period.bucketEnd(row.getPeriodStart(), request.getEndDate()));
        row.setPeriodLabel(period.label(row.getPeriodStart(),
                request.getStartDate(), request.getEndDate()));

        row.setBuyRatePerKg(perKg(row.getAmountBought(), row.getWeightBought()));
        row.setSellRatePerKg(perKg(row.getAmountSold(), row.getWeightSold()));
        row.setOwedToSuppliers(MoneyRules.money(
                row.getAmountBought().subtract(row.getPaidToSuppliers())));

        row.setBirdVariance(row.getBirdsBought() - row.getBirdsSold());

        BigDecimal grossMargin = MoneyRules.money(
                row.getAmountSold().subtract(row.getAmountBought()));
        row.setGrossMargin(grossMargin);
        row.setNetMargin(MoneyRules.money(grossMargin.subtract(row.getPurchaseExpenses())));
        row.setMarginPerKg(MoneyRules.money(
                row.getSellRatePerKg().subtract(row.getBuyRatePerKg())));

        if (row.getAmountBought().signum() > 0) {
            row.setMarginPercent(grossMargin.multiply(BigDecimal.valueOf(100))
                    .divide(row.getAmountBought(), 2, RoundingMode.HALF_UP));
        } else {
            row.setMarginPercent(BigDecimal.ZERO.setScale(2));
        }

        // Shrinkage only means something when both sides were recorded.
        boolean comparable = row.getPurchaseCount() > 0 && row.getSaleCount() > 0;
        row.setComparable(comparable);

        if (comparable && row.getWeightBought().signum() > 0) {
            BigDecimal loss = MoneyRules.weight(
                    row.getWeightBought().subtract(row.getWeightSold()));
            row.setWeightLoss(loss);
            row.setWeightLossPercent(loss.multiply(BigDecimal.valueOf(100))
                    .divide(row.getWeightBought(), 2, RoundingMode.HALF_UP));
        }
    }

    private CombinedSummaryResponse summarise(SalesReportRequest request,
            List<CombinedSummaryRow> rows, ReportPeriod period) {

        CombinedSummaryResponse response = new CombinedSummaryResponse();
        response.setStartDate(request.getStartDate());
        response.setEndDate(request.getEndDate());
        response.setPeriodLabel(period.name());
        response.setPeriods(rows);

        BigDecimal weightBought = BigDecimal.ZERO;
        BigDecimal weightSold = BigDecimal.ZERO;
        BigDecimal amountBought = BigDecimal.ZERO;
        BigDecimal amountSold = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        BigDecimal recovered = BigDecimal.ZERO;
        BigDecimal pending = BigDecimal.ZERO;

        for (CombinedSummaryRow row : rows) {
            response.setPurchaseCount(response.getPurchaseCount() + row.getPurchaseCount());
            response.setBirdsBought(response.getBirdsBought() + row.getBirdsBought());
            response.setSaleCount(response.getSaleCount() + row.getSaleCount());
            response.setBirdsSold(response.getBirdsSold() + row.getBirdsSold());

            weightBought = weightBought.add(row.getWeightBought());
            weightSold = weightSold.add(row.getWeightSold());
            amountBought = amountBought.add(row.getAmountBought());
            amountSold = amountSold.add(row.getAmountSold());
            expenses = expenses.add(row.getPurchaseExpenses());
            paid = paid.add(row.getPaidToSuppliers());
            recovered = recovered.add(row.getRecoveredFromCustomers());
            pending = pending.add(row.getPendingFromCustomers());

            if (row.isComparable()) {
                response.setComparablePeriods(response.getComparablePeriods() + 1);
            } else if (row.getSaleCount() > 0) {
                response.setPeriodsMissingPurchases(response.getPeriodsMissingPurchases() + 1);
            } else if (row.getPurchaseCount() > 0) {
                response.setPeriodsMissingSales(response.getPeriodsMissingSales() + 1);
            }
        }

        response.setWeightBought(MoneyRules.weight(weightBought));
        response.setWeightSold(MoneyRules.weight(weightSold));
        response.setAmountBought(MoneyRules.money(amountBought));
        response.setAmountSold(MoneyRules.money(amountSold));
        response.setPurchaseExpenses(MoneyRules.money(expenses));
        response.setPaidToSuppliers(MoneyRules.money(paid));
        response.setOwedToSuppliers(MoneyRules.money(amountBought.subtract(paid)));
        response.setRecoveredFromCustomers(MoneyRules.money(recovered));
        response.setPendingFromCustomers(MoneyRules.money(pending));

        response.setBuyRatePerKg(perKg(response.getAmountBought(), weightBought));
        response.setSellRatePerKg(perKg(response.getAmountSold(), weightSold));
        response.setBirdVariance(response.getBirdsBought() - response.getBirdsSold());

        BigDecimal grossMargin = MoneyRules.money(amountSold.subtract(amountBought));
        response.setGrossMargin(grossMargin);
        response.setNetMargin(MoneyRules.money(grossMargin.subtract(expenses)));
        response.setMarginPerKg(MoneyRules.money(
                response.getSellRatePerKg().subtract(response.getBuyRatePerKg())));
        response.setMarginPercent(amountBought.signum() > 0
                ? grossMargin.multiply(BigDecimal.valueOf(100))
                        .divide(amountBought, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2));

        if (weightBought.signum() > 0 && response.getComparablePeriods() > 0) {
            BigDecimal loss = MoneyRules.weight(weightBought.subtract(weightSold));
            response.setWeightLoss(loss);
            response.setWeightLossPercent(loss.multiply(BigDecimal.valueOf(100))
                    .divide(weightBought, 2, RoundingMode.HALF_UP));
        }

        // The honest headline: how far apart the two sides are.
        if (response.getBirdsBought() > 0) {
            response.setSoldToBoughtRatio(BigDecimal.valueOf(response.getBirdsSold())
                    .divide(BigDecimal.valueOf(response.getBirdsBought()), 1, RoundingMode.HALF_UP));
        }
        response.setCoverageWarning(coverageWarning(response));

        return response;
    }

    /**
     * Says plainly when the margin cannot be believed, rather than leaving a
     * reader to infer it from the coverage counts.
     */
    private String coverageWarning(CombinedSummaryResponse response) {
        if (response.getPurchaseCount() == 0) {
            return "No purchases are recorded in this range, so nothing can be compared. "
                    + "The sale figures are correct on their own.";
        }
        if (response.getSaleCount() == 0) {
            return "No sales are recorded in this range, so nothing can be compared.";
        }
        if (response.getSoldToBoughtRatio() != null
                && response.getSoldToBoughtRatio().compareTo(BigDecimal.valueOf(2)) > 0) {
            return String.format(
                    "Only %d purchase(s) are recorded against %d sales, and %s times as many "
                    + "birds were sold as bought. The purchase side is largely unrecorded, so the "
                    + "margin figures are inflated by that gap and should not be read as profit.",
                    response.getPurchaseCount(), response.getSaleCount(),
                    response.getSoldToBoughtRatio().toPlainString());
        }
        if (response.getPeriodsMissingPurchases() > 0) {
            return String.format("%d period(s) have sales but no recorded purchases; "
                    + "their margin is overstated.", response.getPeriodsMissingPurchases());
        }
        return null;
    }

    private CombinedSummaryRow rowFor(Map<LocalDate, CombinedSummaryRow> byPeriod, LocalDate start) {
        return byPeriod.computeIfAbsent(start, key -> {
            CombinedSummaryRow row = new CombinedSummaryRow();
            row.setPeriodStart(key);
            return row;
        });
    }

    /** A period present on only one side leaves the other's fields null. */
    private void zeroNulls(CombinedSummaryRow row) {
        if (row.getWeightBought() == null) row.setWeightBought(MoneyRules.weight(BigDecimal.ZERO));
        if (row.getWeightSold() == null) row.setWeightSold(MoneyRules.weight(BigDecimal.ZERO));
        if (row.getAmountBought() == null) row.setAmountBought(MoneyRules.money(BigDecimal.ZERO));
        if (row.getAmountSold() == null) row.setAmountSold(MoneyRules.money(BigDecimal.ZERO));
        if (row.getPurchaseExpenses() == null) row.setPurchaseExpenses(MoneyRules.money(BigDecimal.ZERO));
        if (row.getPaidToSuppliers() == null) row.setPaidToSuppliers(MoneyRules.money(BigDecimal.ZERO));
        if (row.getRecoveredFromCustomers() == null) row.setRecoveredFromCustomers(MoneyRules.money(BigDecimal.ZERO));
        if (row.getPendingFromCustomers() == null) row.setPendingFromCustomers(MoneyRules.money(BigDecimal.ZERO));
    }

    private BigDecimal perKg(BigDecimal amount, BigDecimal weight) {
        if (weight == null || weight.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return amount.divide(weight, 2, RoundingMode.HALF_UP);
    }

    @SuppressWarnings("unchecked")
    private List<Object[]> rows(Query query) {
        return query.getResultList();
    }

    private static long toLong(Object v) { return v == null ? 0 : ((Number) v).longValue(); }

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
