package com.app.service.report;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.CustomerStatementDTO;
import com.app.dto.trading.TradingEntryView;
import com.app.dto.trading.TradingPartyRow;
import com.app.dto.trading.TradingReport;
import com.app.entity.Party;
import com.app.entity.TradingEntry;
import com.app.exception.ResourceNotFoundException;
import com.app.repository.PartyRepository;
import com.app.repository.TradingEntryRepository;
import com.app.service.LedgerService;
import com.app.utility.MoneyRules;

/**
 * The wholesale side's own ledger and reports.
 *
 * Separate from the retail ones deliberately. The two are different businesses measured
 * differently - a route is read per trip and per driver, trading per party - and route 9
 * spent six months inside the route reports distorting those very figures.
 *
 * What is not duplicated is the accounting. A party's balance and its statement come from
 * the same customer_ledger every retail customer uses, because that is where route 9's
 * 44,03,490 and its six-month history live. Keeping a second set of balances in trading
 * would have meant two numbers for one debt and no way to say which was right.
 */
@Service
public class TradingReportService {

    /** A ledger view is read on screen; a party with 571 entries should not hang it. */
    private static final int MAX_ROWS = 1000;

    private final TradingEntryRepository entryRepository;
    private final PartyRepository partyRepository;
    private final LedgerService ledgerService;

    public TradingReportService(TradingEntryRepository entryRepository,
                                PartyRepository partyRepository,
                                LedgerService ledgerService) {
        this.entryRepository = entryRepository;
        this.partyRepository = partyRepository;
        this.ledgerService = ledgerService;
    }

    // ---- the party list --------------------------------------------------

    /**
     * Every party, with what they owe and what they traded in the period.
     *
     * Ordered by balance: the point of the screen is who to chase, and the largest single
     * wholesale debt is 13,40,970 against one party.
     */
    @Transactional(readOnly = true)
    public List<TradingPartyRow> parties(LocalDate from, LocalDate to) {
        // One aggregate query for the period's activity, then one pass over the parties -
        // rather than a query per party, which is 13 round trips for 13 parties and grows.
        Map<Long, Object[]> activity = new LinkedHashMap<>();
        for (Object[] row : entryRepository.summariseByParty(from, to)) {
            activity.put(asLong(row[0]), row);
        }

        List<TradingPartyRow> rows = new ArrayList<>();
        for (Party party : partyRepository.findAll()) {
            Object[] totals = activity.get(party.getId());

            rows.add(new TradingPartyRow(
                    party.getId(),
                    party.getName(),
                    party.getOwner(),
                    party.getCity(),
                    party.getMobileNo(),
                    party.getCustomer() == null ? null : party.getCustomer().getId(),
                    // The ledger's figure, not the party's own column: that one is typed
                    // by hand and the ledger's is maintained on every entry and payment.
                    party.getCustomer() == null
                            ? MoneyRules.money(BigDecimal.ZERO)
                            : ledgerService.getCurrentBalance(party.getCustomer()),
                    party.getVehicleNumbers() == null ? 0 : party.getVehicleNumbers().size(),
                    totals == null ? 0L : asLong(totals[2]),
                    totals == null ? 0L : asLong(totals[3]),
                    totals == null ? MoneyRules.weight(BigDecimal.ZERO) : MoneyRules.weight(asDecimal(totals[4])),
                    totals == null ? MoneyRules.money(BigDecimal.ZERO) : MoneyRules.money(asDecimal(totals[5])),
                    totals == null ? MoneyRules.money(BigDecimal.ZERO) : MoneyRules.money(asDecimal(totals[6])),
                    totals == null ? null : (LocalDate) totals[7],
                    totals == null ? null : (LocalDate) totals[8],
                    Boolean.TRUE.equals(party.getIsObsolete())));
        }

        rows.sort((left, right) -> right.balance().compareTo(left.balance()));
        return rows;
    }

    // ---- one party's ledger ----------------------------------------------

    /**
     * A party's trading entries, and the account statement behind them.
     *
     * Both, because they answer different questions and a reader needs them together: the
     * entries say what was delivered on which load, and the statement says what that did
     * to the balance. They agree by construction - every entry posted the ledger row the
     * statement reads.
     */
    @Transactional(readOnly = true)
    public PartyLedger ledger(Long partyId, LocalDate from, LocalDate to) {
        Party party = partyRepository.findById(partyId)
                .orElseThrow(() -> new ResourceNotFoundException("Party " + partyId + " was not found."));

        List<TradingEntryView> entries = entryRepository
                .findForParty(partyId, from, to, Limit.of(MAX_ROWS))
                .stream()
                .map(TradingEntryView::of)
                .toList();

        // Null when the party has no ledger account. That is a real state - a party can be
        // recorded before it is billable - and the screen says so rather than showing a
        // zero balance that looks like a settled account.
        CustomerStatementDTO statement = party.getCustomer() == null
                ? null
                : ledgerService.getCustomerStatement(party.getCustomer().getId(), from, to);

        return new PartyLedger(
                party.getId(),
                party.getName(),
                party.getOwner(),
                party.getCity(),
                party.getMobileNo(),
                party.getCustomer() == null ? null : party.getCustomer().getId(),
                party.getVehicleNumbers() == null ? List.of() : List.copyOf(party.getVehicleNumbers()),
                entries,
                statement);
    }

    /** A party, its loads, and the account those loads were billed to. */
    public record PartyLedger(
            Long partyId,
            String name,
            String owner,
            String city,
            String mobileNo,
            Long customerId,
            List<String> vehicleNumbers,
            List<TradingEntryView> entries,
            /** Null when the party has no ledger account, so cannot be billed. */
            CustomerStatementDTO statement) {
    }

    // ---- the report -------------------------------------------------------

    @Transactional(readOnly = true)
    public TradingReport report(LocalDate from, LocalDate to) {
        List<TradingReport.PartyLine> parties = new ArrayList<>();

        long totalEntries = 0;
        long totalBirds = 0;
        BigDecimal totalKg = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        BigDecimal totalReceived = BigDecimal.ZERO;

        // Balances come from the party list, which reads them off the ledger - so the
        // report's outstanding figure is the same number the statement would state.
        Map<Long, BigDecimal> balances = new LinkedHashMap<>();
        for (TradingPartyRow row : parties(from, to)) {
            balances.put(row.partyId(), row.balance());
        }

        for (Object[] row : entryRepository.summariseByParty(from, to)) {
            Long partyId = asLong(row[0]);
            long entries = asLong(row[2]);
            long birds = asLong(row[3]);
            BigDecimal kilograms = MoneyRules.weight(asDecimal(row[4]));
            BigDecimal amount = MoneyRules.money(asDecimal(row[5]));
            BigDecimal received = MoneyRules.money(asDecimal(row[6]));

            parties.add(new TradingReport.PartyLine(
                    partyId,
                    (String) row[1],
                    entries, birds, kilograms, amount, received,
                    ratePerKg(amount, kilograms),
                    balances.getOrDefault(partyId, MoneyRules.money(BigDecimal.ZERO)),
                    (LocalDate) row[7],
                    (LocalDate) row[8]));

            totalEntries += entries;
            totalBirds += birds;
            totalKg = totalKg.add(kilograms);
            totalAmount = totalAmount.add(amount);
            totalReceived = totalReceived.add(received);
        }

        List<TradingReport.DayLine> days = new ArrayList<>();
        for (Object[] row : entryRepository.summariseByDay(from, to)) {
            BigDecimal kilograms = MoneyRules.weight(asDecimal(row[3]));
            BigDecimal amount = MoneyRules.money(asDecimal(row[4]));
            days.add(new TradingReport.DayLine(
                    (LocalDate) row[0],
                    asLong(row[1]),
                    asLong(row[2]),
                    kilograms,
                    amount,
                    MoneyRules.money(asDecimal(row[5])),
                    ratePerKg(amount, kilograms)));
        }

        /*
         * Outstanding is the sum of the parties' ledger balances, not amount minus
         * received. Those differ, and the difference is real: a party carries a balance
         * from before the period being reported, and 44,03,490 of route 9's did.
         */
        BigDecimal outstanding = balances.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        TradingReport.Totals totals = new TradingReport.Totals(
                totalEntries,
                parties.size(),
                totalBirds,
                MoneyRules.weight(totalKg),
                MoneyRules.money(totalAmount),
                MoneyRules.money(totalReceived),
                MoneyRules.money(outstanding),
                ratePerKg(totalAmount, totalKg));

        return new TradingReport(from, to, totals, parties, days);
    }

    /** Realised rate over a period: amount billed over weight sold, zero-safe. */
    private static BigDecimal ratePerKg(BigDecimal amount, BigDecimal kilograms) {
        if (kilograms == null || kilograms.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return amount.divide(kilograms, 2, RoundingMode.HALF_UP);
    }

    private static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }
}
