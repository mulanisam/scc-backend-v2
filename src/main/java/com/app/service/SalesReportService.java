package com.app.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.report.ReportGroupBy;
import com.app.dto.report.ReportTotals;
import com.app.dto.report.SalesDetailRow;
import com.app.dto.report.SalesReportRequest;
import com.app.dto.report.SalesReportResponse;
import com.app.dto.report.SalesSummaryRow;
import com.app.repository.SalesReportQueryRepository;
import com.app.utility.MoneyRules;

/**
 * Builds the sales reports.
 *
 * Two shapes cover what the previous six half-implemented report branches were
 * trying to do:
 *
 *  - detail: transaction lines for any combination of date range, route,
 *    customer, driver, vehicle and city
 *  - summary: those transactions aggregated per time bucket crossed with one
 *    business dimension, with the closing ledger balance for each bucket
 *
 * Both always carry totals, which the previous reports never did.
 */
@Service
public class SalesReportService {

    private static final Logger logger = LoggerFactory.getLogger(SalesReportService.class);

    @Autowired
    private SalesReportQueryRepository reportRepository;

    @Transactional(readOnly = true)
    public SalesReportResponse detailReport(SalesReportRequest request) {
        validate(request);
        logger.info("Sales detail report {} to {} (route={}, customer={}, driver={})",
                request.getStartDate(), request.getEndDate(),
                request.getRouteId(), request.getCustomerId(), request.getDriverId());

        List<SalesDetailRow> rows = reportRepository.findDetail(request);

        SalesReportResponse response = baseResponse(request, "Sales transactions");
        response.setDetail(rows);
        response.setTotals(totalsFromDetail(rows));
        return response;
    }

    @Transactional(readOnly = true)
    public SalesReportResponse summaryReport(SalesReportRequest request) {
        validate(request);
        logger.info("Sales summary report {} to {} period={} groupBy={}",
                request.getStartDate(), request.getEndDate(),
                request.getPeriod(), request.getGroupBy());

        List<SalesSummaryRow> rows = reportRepository.findSummary(request);
        applyClosingBalances(request, rows);
        rows.forEach(row -> row.setAverageRate(averageRate(row.getAmount(), row.getWeight())));

        SalesReportResponse response = baseResponse(request,
                request.getGroupBy().label() + " summary");
        response.setSummary(rows);
        response.setTotals(totalsFromSummary(rows));
        return response;
    }

    /**
     * Attaches the closing ledger balance to each summary row.
     *
     * For a customer-grouped report this is that customer's balance at the end of
     * the bucket. For any other dimension it is the sum of the balances of the
     * customers who traded within that dimension, since a route does not have a
     * balance of its own - its customers do.
     */
    private void applyClosingBalances(SalesReportRequest request, List<SalesSummaryRow> rows) {
        if (rows.isEmpty()) {
            return;
        }

        Map<String, BigDecimal> balances = reportRepository.findClosingBalances(request);
        ReportGroupBy groupBy = request.getGroupBy();

        if (groupBy == ReportGroupBy.CUSTOMER) {
            for (SalesSummaryRow row : rows) {
                row.setClosingBalance(MoneyRules.money(
                        balances.get(row.getPeriodStart() + "|" + row.getDimensionId())));
            }
            return;
        }

        Map<Long, List<Long>> customersByDimension = reportRepository.findCustomersByDimension(request);

        for (SalesSummaryRow row : rows) {
            List<Long> customerIds = groupBy.isGrouped()
                    ? customersByDimension.getOrDefault(row.getDimensionId(), List.of())
                    : customersByDimension.values().stream().flatMap(List::stream).distinct().toList();

            BigDecimal total = BigDecimal.ZERO;
            for (Long customerId : customerIds) {
                BigDecimal balance = balances.get(row.getPeriodStart() + "|" + customerId);
                if (balance != null) {
                    total = total.add(balance);
                }
            }
            row.setClosingBalance(MoneyRules.money(total));
        }
    }

    private SalesReportResponse baseResponse(SalesReportRequest request, String title) {
        SalesReportResponse response = new SalesReportResponse();
        response.setTitle(title);
        response.setStartDate(request.getStartDate());
        response.setEndDate(request.getEndDate());
        response.setPeriodLabel(request.getPeriod().name());
        response.setGroupByLabel(request.getGroupBy().label());
        response.setAppliedFilters(describeFilters(request));
        return response;
    }

    /** Human-readable list of the filters in force, for the report heading. */
    private List<String> describeFilters(SalesReportRequest request) {
        List<String> filters = new ArrayList<>();
        if (request.getRouteId() != null)    filters.add("Route #" + request.getRouteId());
        if (request.getCustomerId() != null) filters.add("Customer #" + request.getCustomerId());
        if (request.getDriverId() != null)   filters.add("Driver #" + request.getDriverId());
        if (request.getVehicleId() != null)  filters.add("Vehicle #" + request.getVehicleId());
        if (request.getCityId() != null)     filters.add("City #" + request.getCityId());
        if (request.isExcludeObsolete())     filters.add("Active customers only");
        return filters;
    }

    private ReportTotals totalsFromDetail(List<SalesDetailRow> rows) {
        ReportTotals totals = zeroTotals();
        totals.setRowCount(rows.size());
        totals.setTransactionCount(rows.size());

        for (SalesDetailRow row : rows) {
            totals.setBirds(totals.getBirds() + (row.getBirds() == null ? 0 : row.getBirds()));
            totals.setWeight(totals.getWeight().add(MoneyRules.weight(row.getWeight())));
            totals.setAmount(totals.getAmount().add(MoneyRules.money(row.getAmount())));
            totals.setPayment(totals.getPayment().add(MoneyRules.money(row.getPayment())));
            totals.setPending(totals.getPending().add(MoneyRules.money(row.getPending())));
        }
        totals.setAverageRate(averageRate(totals.getAmount(), totals.getWeight()));
        return totals;
    }

    private ReportTotals totalsFromSummary(List<SalesSummaryRow> rows) {
        ReportTotals totals = zeroTotals();
        totals.setRowCount(rows.size());

        for (SalesSummaryRow row : rows) {
            totals.setTransactionCount(totals.getTransactionCount() + row.getTransactionCount());
            totals.setBirds(totals.getBirds() + row.getBirds());
            totals.setWeight(totals.getWeight().add(MoneyRules.weight(row.getWeight())));
            totals.setAmount(totals.getAmount().add(MoneyRules.money(row.getAmount())));
            totals.setPayment(totals.getPayment().add(MoneyRules.money(row.getPayment())));
            totals.setPending(totals.getPending().add(MoneyRules.money(row.getPending())));
        }
        totals.setAverageRate(averageRate(totals.getAmount(), totals.getWeight()));
        return totals;
    }

    private ReportTotals zeroTotals() {
        ReportTotals totals = new ReportTotals();
        totals.setWeight(BigDecimal.ZERO);
        totals.setAmount(BigDecimal.ZERO);
        totals.setPayment(BigDecimal.ZERO);
        totals.setPending(BigDecimal.ZERO);
        return totals;
    }

    /** Realised rate per kilogram. Zero weight yields zero rather than an error. */
    private BigDecimal averageRate(BigDecimal amount, BigDecimal weight) {
        if (weight == null || weight.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return amount.divide(weight, 2, RoundingMode.HALF_UP);
    }

    private void validate(SalesReportRequest request) {
        if (request.getStartDate() == null || request.getEndDate() == null) {
            throw new IllegalArgumentException("Both a start date and an end date are required.");
        }
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("End date " + request.getEndDate()
                    + " is before start date " + request.getStartDate() + ".");
        }
    }
}
