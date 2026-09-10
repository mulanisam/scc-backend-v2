package com.app.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.app.dto.report.ReportGroupBy;
import com.app.dto.report.ReportPeriod;
import com.app.dto.report.SalesDetailRow;
import com.app.dto.report.SalesSummaryRow;
import com.app.dto.report.SalesReportRequest;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;

/**
 * Report queries over the sales data.
 *
 * Written as dynamic native SQL because the grouping dimension and time bucket
 * vary per request. Only fragments from the ReportPeriod and ReportGroupBy enums
 * are interpolated; every value from the request is bound as a parameter, so no
 * caller input reaches the query text.
 *
 * This replaces four hand-written native queries in SaleRepository, of which one
 * ignored its own date-range parameters entirely and four report dimensions were
 * never implemented at all.
 */
@Repository
public class SalesReportQueryRepository {

    /**
     * Shared FROM clause. Every dimension is joined once so the grouping can
     * vary without changing the shape of the query. LEFT JOIN on vehicle because
     * sale.vehicle_no is nullable.
     */
    private static final String FROM_SALES = """
            FROM sale s
            JOIN customer cust ON cust.id = s.customer_id
            JOIN city city     ON city.id = cust.city_id
            JOIN route r       ON r.id = s.route_id
            JOIN driver d      ON d.id = s.driver_id
            LEFT JOIN vehicle v ON v.id = s.vehicle_no
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /** Transaction lines matching the filters, oldest first. */
    public List<SalesDetailRow> findDetail(SalesReportRequest request) {
        StringBuilder sql = new StringBuilder("""
                SELECT s.id, s.date, r.name, city.name, cust.name, cust.shop_name,
                       d.name, v.vehicle_no,
                       s.birds, s.kilograms, s.rate, s.amount, s.payment, s.pending,
                       cl.running_balance, s.payment_mode, s.description, s.created_by
                """);
        sql.append(FROM_SALES);
        // The ledger row for this sale carries the balance immediately after it.
        sql.append("""
                LEFT JOIN customer_ledger cl
                       ON cl.reference_type = 'SALE' AND cl.reference_id = s.id
                """);
        appendFilters(sql, request);
        sql.append(" ORDER BY s.date, r.name, cust.name, s.id");

        Query query = entityManager.createNativeQuery(sql.toString());
        bindFilters(query, request);

        List<SalesDetailRow> rows = new ArrayList<>();
        for (Object[] r : castRows(query.getResultList())) {
            SalesDetailRow row = new SalesDetailRow();
            row.setSaleId(toLong(r[0]));
            row.setDate(toLocalDate(r[1]));
            row.setRoute((String) r[2]);
            row.setCity((String) r[3]);
            row.setCustomer((String) r[4]);
            row.setShopName((String) r[5]);
            row.setDriver((String) r[6]);
            row.setVehicle((String) r[7]);
            row.setBirds(toInteger(r[8]));
            row.setWeight(toBigDecimal(r[9]));
            row.setRate(toBigDecimal(r[10]));
            row.setAmount(toBigDecimal(r[11]));
            row.setPayment(toBigDecimal(r[12]));
            row.setPending(toBigDecimal(r[13]));
            row.setBalanceAfter(toBigDecimal(r[14]));
            row.setPaymentMode((String) r[15]);
            row.setDescription((String) r[16]);
            row.setCreatedBy((String) r[17]);
            rows.add(row);
        }
        return rows;
    }

    /** Aggregates per period bucket crossed with the chosen dimension. */
    public List<SalesSummaryRow> findSummary(SalesReportRequest request) {
        ReportPeriod period = request.getPeriod();
        ReportGroupBy groupBy = request.getGroupBy();

        String bucket = period.bucketExpression("s.date");

        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ").append(bucket).append(" AS period_start, ")
           .append(groupBy.idExpression()).append(" AS dim_id, ")
           .append(groupBy.nameExpression()).append(" AS dim_name, ")
           .append("""
                   COUNT(*)                       AS txn_count,
                   COUNT(DISTINCT cust.id)        AS customer_count,
                   COALESCE(SUM(s.birds), 0)      AS birds,
                   COALESCE(SUM(s.kilograms), 0)  AS weight,
                   COALESCE(SUM(s.amount), 0)     AS amount,
                   COALESCE(SUM(s.payment), 0)    AS payment,
                   COALESCE(SUM(s.pending), 0)    AS pending
                   """);
        sql.append(FROM_SALES);
        appendFilters(sql, request);

        sql.append(" GROUP BY period_start");
        if (groupBy.isGrouped()) {
            sql.append(", dim_id, dim_name");
        }
        sql.append(" ORDER BY period_start");
        if (groupBy.isGrouped()) {
            sql.append(", dim_name");
        }

        Query query = entityManager.createNativeQuery(sql.toString());
        bindFilters(query, request);

        List<SalesSummaryRow> rows = new ArrayList<>();
        for (Object[] r : castRows(query.getResultList())) {
            SalesSummaryRow row = new SalesSummaryRow();
            LocalDate start = toLocalDate(r[0]);
            row.setPeriodStart(period.isBucketed() ? start : request.getStartDate());
            row.setPeriodEnd(period.bucketEnd(start, request.getEndDate()));
            row.setPeriodLabel(period.label(start, request.getStartDate(), request.getEndDate()));
            row.setDimensionId(toLong(r[1]));
            row.setDimensionName((String) r[2]);
            row.setTransactionCount(toLong(r[3]) == null ? 0 : toLong(r[3]));
            row.setCustomerCount(toLong(r[4]) == null ? 0 : toLong(r[4]));
            row.setBirds(toLong(r[5]) == null ? 0 : toLong(r[5]));
            row.setWeight(toBigDecimal(r[6]));
            row.setAmount(toBigDecimal(r[7]));
            row.setPayment(toBigDecimal(r[8]));
            row.setPending(toBigDecimal(r[9]));
            rows.add(row);
        }
        return rows;
    }

    /**
     * Closing ledger balance per customer at the end of each period bucket,
     * keyed as "periodStart|customerId".
     *
     * Uses the last ledger entry on or before the bucket end, which is the
     * customer's actual outstanding balance at that moment - not the sum of the
     * period's own pending, which ignores everything owed before it.
     */
    public Map<String, BigDecimal> findClosingBalances(SalesReportRequest request) {
        ReportPeriod period = request.getPeriod();
        String bucket = period.bucketExpression("cl.transaction_date");

        String sql = """
                SELECT period_start, customer_id, running_balance FROM (
                  SELECT %s AS period_start, cl.customer_id, cl.running_balance,
                         ROW_NUMBER() OVER (
                             PARTITION BY cl.customer_id, %s
                             ORDER BY cl.transaction_date DESC, cl.id DESC
                         ) AS rn
                  FROM customer_ledger cl
                  WHERE cl.transaction_date <= :endDate
                ) ranked
                WHERE rn = 1
                """.formatted(bucket, bucket);

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("endDate", request.getEndDate());

        Map<String, BigDecimal> balances = new LinkedHashMap<>();
        for (Object[] r : castRows(query.getResultList())) {
            LocalDate start = toLocalDate(r[0]);
            Long customerId = toLong(r[1]);
            balances.put(start + "|" + customerId, toBigDecimal(r[2]));
        }
        return balances;
    }

    /** Customer ids per dimension value, so group balances can be summed. */
    public Map<Long, List<Long>> findCustomersByDimension(SalesReportRequest request) {
        ReportGroupBy groupBy = request.getGroupBy();
        if (!groupBy.isGrouped()) {
            return Map.of();
        }

        StringBuilder sql = new StringBuilder("SELECT DISTINCT ")
                .append(groupBy.idExpression()).append(" AS dim_id, cust.id ");
        sql.append(FROM_SALES);
        appendFilters(sql, request);

        Query query = entityManager.createNativeQuery(sql.toString());
        bindFilters(query, request);

        Map<Long, List<Long>> byDimension = new LinkedHashMap<>();
        for (Object[] r : castRows(query.getResultList())) {
            byDimension.computeIfAbsent(toLong(r[0]), k -> new ArrayList<>()).add(toLong(r[1]));
        }
        return byDimension;
    }

    // ---- filters -----------------------------------------------------------

    private void appendFilters(StringBuilder sql, SalesReportRequest request) {
        sql.append(" WHERE s.date BETWEEN :startDate AND :endDate ");
        if (request.getRouteId() != null)    sql.append(" AND s.route_id = :routeId ");
        if (request.getCustomerId() != null) sql.append(" AND s.customer_id = :customerId ");
        if (request.getDriverId() != null)   sql.append(" AND s.driver_id = :driverId ");
        if (request.getVehicleId() != null)  sql.append(" AND s.vehicle_no = :vehicleId ");
        if (request.getCityId() != null)     sql.append(" AND cust.city_id = :cityId ");
        if (request.isExcludeObsolete())     sql.append(" AND cust.obsolete = FALSE ");
    }

    private void bindFilters(Query query, SalesReportRequest request) {
        query.setParameter("startDate", request.getStartDate());
        query.setParameter("endDate", request.getEndDate());
        if (request.getRouteId() != null)    query.setParameter("routeId", request.getRouteId());
        if (request.getCustomerId() != null) query.setParameter("customerId", request.getCustomerId());
        if (request.getDriverId() != null)   query.setParameter("driverId", request.getDriverId());
        if (request.getVehicleId() != null)  query.setParameter("vehicleId", request.getVehicleId());
        if (request.getCityId() != null)     query.setParameter("cityId", request.getCityId());
    }

    // ---- result coercion ---------------------------------------------------

    @SuppressWarnings("unchecked")
    private List<Object[]> castRows(List<?> results) {
        return (List<Object[]>) results;
    }

    private static Long toLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static Integer toInteger(Object value) {
        return value == null ? null : ((Number) value).intValue();
    }

    private static BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        return BigDecimal.valueOf(((Number) value).doubleValue());
    }

    private static LocalDate toLocalDate(Object value) {
        if (value == null) return null;
        if (value instanceof Date sqlDate) return sqlDate.toLocalDate();
        if (value instanceof LocalDate localDate) return localDate;
        return LocalDate.parse(value.toString().substring(0, 10));
    }
}
