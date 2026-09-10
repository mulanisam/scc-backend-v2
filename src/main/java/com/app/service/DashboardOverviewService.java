package com.app.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.dashboard.DashboardExceptions;
import com.app.dto.dashboard.DashboardMargin;
import com.app.dto.dashboard.DashboardOverviewDTO;
import com.app.dto.dashboard.DashboardPurchaseTotals;
import com.app.dto.dashboard.DashboardReceivables;
import com.app.dto.dashboard.DashboardSalesTotals;
import com.app.repository.DashboardQueryRepository;
import com.app.utility.MoneyRules;

/**
 * Assembles the dashboard.
 *
 * The date is honoured throughout - the previous implementation took a date on the
 * screen, passed it to the API, and then called LocalDate.now() anyway, so the
 * picker did nothing.
 *
 * Windows are deliberately mixed. A day on its own is a screen of zeros until the
 * first trip is entered, which is most of the morning, so the day sits beside
 * month- and year-to-date and a fortnight of history.
 */
@Service
public class DashboardOverviewService {

    private static final Logger logger = LoggerFactory.getLogger(DashboardOverviewService.class);

    /** Days of history on the trend, including the selected day. */
    private static final int TREND_DAYS = 14;
    private static final int TOP_DEBTORS = 12;
    private static final int STALE_DAYS = 30;

    @Autowired
    private DashboardQueryRepository dashboardRepository;

    @Transactional(readOnly = true)
    public DashboardOverviewDTO getOverview(LocalDate asOfDate) {
        LocalDate date = asOfDate == null ? LocalDate.now() : asOfDate;
        logger.info("Building dashboard overview as of {}", date);

        LocalDate monthStart = date.withDayOfMonth(1);
        LocalDate yearStart = date.withDayOfYear(1);

        DashboardOverviewDTO overview = new DashboardOverviewDTO();
        overview.setAsOfDate(date);
        overview.setGeneratedAt(LocalDateTime.now());

        overview.setDay(dashboardRepository.salesTotals("Selected day", date, date));
        overview.setPreviousDay(dashboardRepository.salesTotals("Previous day",
                date.minusDays(1), date.minusDays(1)));
        overview.setMonthToDate(dashboardRepository.salesTotals("Month to date", monthStart, date));
        overview.setYearToDate(dashboardRepository.salesTotals("Year to date", yearStart, date));

        overview.setPurchaseMonthToDate(
                dashboardRepository.purchaseTotals("Month to date", monthStart, date));
        overview.setPurchaseYearToDate(
                dashboardRepository.purchaseTotals("Year to date", yearStart, date));
        overview.setMarginYearToDate(
                margin(overview.getYearToDate(), overview.getPurchaseYearToDate()));

        overview.setDailyTrend(dashboardRepository.dailyTrend(date.minusDays(TREND_DAYS - 1L), date));
        overview.setRoutesOnDay(dashboardRepository.routeBreakdown(date, date));
        overview.setRoutesMonthToDate(dashboardRepository.routeBreakdown(monthStart, date));
        overview.setSuppliersYearToDate(dashboardRepository.supplierBreakdown(yearStart, date));

        overview.setReceivables(receivables());
        overview.setExceptions(withNotes(dashboardRepository.exceptions()));

        LocalDate latest = dashboardRepository.latestTradingDate();
        overview.setLatestTradingDate(latest);
        overview.setTradedOnAsOfDate(overview.getDay().getSaleCount() > 0);

        return overview;
    }

    /**
     * Bought against sold for the year.
     *
     * Marked not comparable unless both sides actually have rows and weight; with
     * 9 purchases against 56,099 sales in this database, a margin figure computed
     * from them would be arithmetic on nothing.
     */
    private DashboardMargin margin(DashboardSalesTotals sales, DashboardPurchaseTotals purchases) {
        DashboardMargin margin = new DashboardMargin();
        margin.setBuyRatePerKg(purchases.getAverageRate());
        margin.setSellRatePerKg(sales.getAverageRate());
        margin.setMarginPerKg(BigDecimal.ZERO.setScale(2));
        margin.setWeightLoss(BigDecimal.ZERO.setScale(3));

        boolean bothSidesPresent = purchases.getPurchaseCount() > 0
                && purchases.getWeightBought().signum() > 0
                && sales.getSaleCount() > 0
                && sales.getWeightSold().signum() > 0;

        if (!bothSidesPresent) {
            margin.setComparable(false);
            margin.setNote(purchases.getPurchaseCount() == 0
                    ? "No purchases are recorded for this year, so cost and margin cannot be compared."
                    : "One side of the comparison has no recorded weight.");
            return margin;
        }

        // Coverage decides whether the comparison means anything at all. Selling
        // far more weight than was bought does not mean stock appeared - it means
        // the purchase side is not being entered. In this database the year's
        // purchases cover well under 1% of the weight sold, which would otherwise
        // produce a "weight loss" of twelve lakh kilograms and a cost per kilo
        // drawn from two deliveries.
        BigDecimal coverage = purchases.getWeightBought()
                .multiply(BigDecimal.valueOf(100))
                .divide(sales.getWeightSold(), 1, RoundingMode.HALF_UP);

        if (coverage.compareTo(BigDecimal.valueOf(50)) < 0) {
            margin.setComparable(false);
            margin.setNote("Purchases cover only " + coverage.stripTrailingZeros().toPlainString()
                    + "% of the weight sold this year (" + purchases.getPurchaseCount()
                    + " purchase(s) against " + sales.getSaleCount() + " sales), so cost, margin and"
                    + " weight loss cannot be measured. Record purchases to make this section work.");
            return margin;
        }

        margin.setComparable(true);
        margin.setMarginPerKg(MoneyRules.money(
                sales.getAverageRate().subtract(purchases.getAverageRate())));
        margin.setWeightLoss(MoneyRules.weight(
                purchases.getWeightBought().subtract(sales.getWeightSold())));

        if (coverage.compareTo(BigDecimal.valueOf(90)) < 0) {
            margin.setNote("Purchases cover " + coverage.stripTrailingZeros().toPlainString()
                    + "% of the weight sold this year, so the margin understates cost.");
        }
        return margin;
    }

    private DashboardReceivables receivables() {
        Object[] bands = dashboardRepository.receivableBands();
        Object[] stale = dashboardRepository.staleReceivables(STALE_DAYS);

        DashboardReceivables receivables = new DashboardReceivables();
        receivables.setTotalOutstanding(MoneyRules.money(decimal(bands[0])));
        receivables.setCustomersWithBalance(number(bands[1]));
        receivables.setCustomersInCredit(number(bands[2]));
        receivables.setCountOver50k(number(bands[3]));
        receivables.setAmountOver50k(MoneyRules.money(decimal(bands[4])));
        receivables.setCountOver20k(number(bands[5]));
        receivables.setAmountOver20k(MoneyRules.money(decimal(bands[6])));
        receivables.setCountOverCreditLimit(number(bands[7]));
        receivables.setAmountOverCreditLimit(MoneyRules.money(decimal(bands[8])));
        receivables.setCountStale30Days(number(stale[0]));
        receivables.setAmountStale30Days(MoneyRules.money(decimal(stale[1])));
        receivables.setTopDebtors(dashboardRepository.topDebtors(TOP_DEBTORS));
        return receivables;
    }

    /** Turns each exception count into a sentence the screen can show as-is. */
    private DashboardExceptions withNotes(DashboardExceptions exceptions) {
        List<String> notes = new ArrayList<>();

        if (exceptions.getFutureDatedSales() > 0) {
            notes.add(exceptions.getFutureDatedSales() + " sale(s) are dated after today, worth "
                    + exceptions.getFutureDatedAmount().toPlainString()
                    + ". They inflate every total that includes them.");
        }
        if (exceptions.getTripsWithBirdMismatch() > 0) {
            notes.add(exceptions.getTripsWithBirdMismatch() + " trip(s) do not tally: loaded birds "
                    + "differ from sold plus mortality plus return to farm by "
                    + exceptions.getBirdMismatchTotal() + " birds in total.");
        }
        if (exceptions.getTripsWhereHeaderDisagreesWithRows() > 0) {
            notes.add(exceptions.getTripsWhereHeaderDisagreesWithRows()
                    + " trip(s) hold a sold-bird count that disagrees with their own sale rows, by "
                    + exceptions.getHeaderVersusRowsBirdDifference()
                    + " birds in total. Bird figures are taken from the sale rows.");
        }
        if (exceptions.getTripsWithoutLoadedWeight() > 0) {
            notes.add(exceptions.getTripsWithoutLoadedWeight()
                    + " trip(s) have no loaded weight recorded, so shrinkage cannot be measured on them.");
        }
        if (exceptions.getTripsWithWeightGap() > 0) {
            notes.add(exceptions.getTripsWithWeightGap() + " trip(s) lost more than 2% of loaded weight, "
                    + exceptions.getWeightGapTotal().toPlainString() + " kg in total.");
        }
        if (exceptions.getUnpaidPurchases() > 0) {
            notes.add(exceptions.getUnpaidPurchases() + " purchase(s) have nothing paid against them, worth "
                    + exceptions.getUnpaidPurchaseAmount().toPlainString() + ".");
        }
        if (exceptions.getCorrectionTrips() > 0) {
            notes.add(exceptions.getCorrectionTrips()
                    + " trip(s) are recorded as corrections to an earlier entry.");
        }

        exceptions.setNotes(notes);
        return exceptions;
    }

    private static long number(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal decimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal amount) return amount;
        return new BigDecimal(value.toString());
    }
}
