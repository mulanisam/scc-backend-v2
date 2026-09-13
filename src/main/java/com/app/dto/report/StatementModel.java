package com.app.dto.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.app.dto.CustomerLedgerDTO;
import com.app.dto.CustomerStatementDTO;
import com.app.dto.LedgerStatementTotals;
import com.app.entity.CustomerLedger.TransactionType;
import com.app.utility.StatementFormat;

/**
 * The statement as the renderer needs it: rows with their wording already decided.
 *
 * A port of buildStatementModel in frontend/src/components/ledger/ledgerStatement.js.
 * The DTO from the ledger service carries the facts; this decides how each of them
 * reads - that a SALE row says "Sale - Madha / Imran" in the Particulars column and
 * carries the voucher "INV-1042", that the statement opens on a "Balance brought
 * forward" line, and that a customer who settles at the door has not "never paid".
 *
 * Kept apart from the drawing code because that is what makes it testable: the wording
 * and the figures can be asserted without rendering a page.
 */
public class StatementModel {

    /** How each transaction type reads, and what its voucher is prefixed with. */
    private static String label(TransactionType type) {
        if (type == null) {
            return "Entry";
        }
        return switch (type) {
            case OPENING_BALANCE -> "Opening balance";
            case SALE -> "Sale";
            case PAYMENT -> "Payment received";
            case CREDIT_NOTE -> "Credit note";
            case DEBIT_NOTE -> "Debit note";
            // Named, not left to a fallback. The JavaScript had no entry for this and
            // printed the raw enum - "ADJUSTMENT" in the Particulars column - which is
            // exactly the thing the statement layout was rewritten to get rid of.
            case ADJUSTMENT -> "Adjustment";
        };
    }

    private static String voucherPrefix(TransactionType type) {
        if (type == null) {
            return "TXN";
        }
        return switch (type) {
            case OPENING_BALANCE -> "O/B";
            case SALE -> "INV";
            case PAYMENT -> "RCPT";
            case CREDIT_NOTE -> "CN";
            case DEBIT_NOTE -> "DN";
            case ADJUSTMENT -> "ADJ";
        };
    }

    /** One line of the statement, with every cell already worded. */
    public record Row(
            TransactionType type,
            LocalDate date,
            String particulars,
            String voucher,
            long birds,
            BigDecimal weight,
            BigDecimal rate,
            BigDecimal debit,
            BigDecimal credit,
            BigDecimal balance,
            String paymentMode,
            boolean backdated,
            /** Superseded by a correction. Shown for trace, greyed and italic. */
            boolean obsolete) {

        public boolean isSale() {
            return type == TransactionType.SALE;
        }

        public boolean hasDebit() {
            return debit != null && debit.signum() > 0;
        }

        public boolean hasCredit() {
            return credit != null && credit.signum() > 0;
        }
    }

    public record Party(
            Long id,
            String name,
            String shopName,
            String mobileNo,
            String address,
            String cityName,
            boolean obsolete,
            /** Null when no limit is enforced, which is most customers. */
            BigDecimal creditLimit) {
    }

    public record Period(
            LocalDate startDate,
            LocalDate endDate,
            LocalDate firstTransactionDate,
            LocalDate lastTransactionDate,
            LocalDateTime generatedAt) {

        /** The period as one line of prose for a heading. */
        public String describe() {
            if (startDate != null && endDate != null) {
                return StatementFormat.date(startDate) + "  to  " + StatementFormat.date(endDate);
            }
            if (firstTransactionDate != null && lastTransactionDate != null) {
                return "All transactions  (" + StatementFormat.date(firstTransactionDate)
                        + "  to  " + StatementFormat.date(lastTransactionDate) + ")";
            }
            return "All transactions";
        }
    }

    /** The last money in, and the last sale out - the first two questions asked. */
    public record LastEvent(LocalDate date, BigDecimal amount, String mode) {
    }

    /**
     * The closing figures, plus two the ledger totals do not carry.
     *
     * @param salesWithCollectionCount sales that were settled, wholly or partly, at the
     *        point of sale
     * @param collectedWithSales how much arrived that way
     *
     * Both are here because most collection in this business is taken on the spot and
     * recorded on the sale row, not as a separate receipt - so a customer can have
     * lakhs in credits against a payment-voucher count of zero, and a summary built on
     * the voucher count alone reads as "never paid".
     */
    public record Totals(
            int rowCount,
            int saleCount,
            int paymentCount,
            int adjustmentCount,
            BigDecimal openingBalance,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            BigDecimal netMovement,
            BigDecimal closingBalance,
            long birds,
            BigDecimal weight,
            BigDecimal averageRate,
            int salesWithCollectionCount,
            BigDecimal collectedWithSales) {
    }

    private final Party party;
    private final Period period;
    private final Totals totals;
    private final List<Row> rows;
    private final Row openingRow;
    private final boolean showOpeningRow;
    private final LastEvent lastPayment;
    private final LastEvent lastSale;

    private StatementModel(Party party, Period period, Totals totals, List<Row> rows,
                           Row openingRow, boolean showOpeningRow,
                           LastEvent lastPayment, LastEvent lastSale) {
        this.party = party;
        this.period = period;
        this.totals = totals;
        this.rows = rows;
        this.openingRow = openingRow;
        this.showOpeningRow = showOpeningRow;
        this.lastPayment = lastPayment;
        this.lastSale = lastSale;
    }

    public static StatementModel of(CustomerStatementDTO statement) {
        List<CustomerLedgerDTO> entries = statement.getEntries() == null
                ? List.of() : statement.getEntries();

        List<Row> rows = new ArrayList<>(entries.size());
        for (CustomerLedgerDTO entry : entries) {
            rows.add(new Row(
                    entry.getTransactionType(),
                    entry.getTransactionDate(),
                    particularsFor(entry),
                    voucherFor(entry),
                    entry.getBirds() == null ? 0L : entry.getBirds().longValue(),
                    zero(entry.getWeight()),
                    zero(entry.getRate()),
                    zero(entry.getDebitAmount()),
                    zero(entry.getCreditAmount()),
                    zero(entry.getRunningBalance()),
                    nullToEmpty(entry.getPaymentMode()),
                    entry.isBackdated(),
                    entry.isObsolete()));
        }

        BigDecimal opening = zero(statement.getOpeningBalance());

        // A statement always says where the balance came from, even when the answer is
        // zero - an unexplained opening figure is the thing customers dispute.
        Row openingRow = new Row(
                TransactionType.OPENING_BALANCE,
                statement.getStartDate() != null
                        ? statement.getStartDate()
                        : (rows.isEmpty() ? null : rows.get(0).date()),
                statement.getStartDate() != null ? "Balance brought forward" : "Opening balance",
                "",
                0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                opening, "", false, false);

        LastEvent lastPayment = null;
        LastEvent lastSale = null;
        int salesWithCollection = 0;
        BigDecimal collectedWithSales = BigDecimal.ZERO;

        /*
         * Last wins, by row order rather than by date.
         *
         * The rows arrive ordered by date and then by id, and that ordering is the
         * statement's own: two payments on the same day are "first" and "last" by the
         * order they were entered, which is the order the balance column already
         * reflects. Comparing dates instead would pick an arbitrary one of the two and
         * could name a figure that does not match the balance beside it.
         */
        for (Row row : rows) {
            if (row.hasCredit()) {
                lastPayment = new LastEvent(row.date(), row.credit(), row.paymentMode());
            }
            if (row.isSale()) {
                lastSale = new LastEvent(row.date(), row.debit(), "");
            }
            if (row.isSale() && row.hasCredit()) {
                salesWithCollection++;
                collectedWithSales = collectedWithSales.add(row.credit());
            }
        }

        LedgerStatementTotals source = statement.getTotals();
        Totals totals = new Totals(
                source == null ? rows.size() : source.getRowCount(),
                source == null ? 0 : source.getSaleCount(),
                source == null ? 0 : source.getPaymentCount(),
                source == null ? 0 : source.getAdjustmentCount(),
                opening,
                source == null ? BigDecimal.ZERO : zero(source.getTotalDebit()),
                source == null ? BigDecimal.ZERO : zero(source.getTotalCredit()),
                source == null ? BigDecimal.ZERO : zero(source.getNetMovement()),
                source == null ? BigDecimal.ZERO : zero(source.getClosingBalance()),
                source == null ? 0L : source.getBirds(),
                source == null ? BigDecimal.ZERO : zero(source.getWeight()),
                source == null ? BigDecimal.ZERO : zero(source.getAverageRate()),
                salesWithCollection,
                collectedWithSales);

        Party party = new Party(
                statement.getCustomerId(),
                nullToEmpty(statement.getCustomerName()),
                nullToEmpty(statement.getShopName()),
                nullToEmpty(statement.getMobileNo()),
                nullToEmpty(statement.getAddress()),
                nullToEmpty(statement.getCityName()),
                statement.isObsolete(),
                statement.isCreditLimitEnabled() ? zero(statement.getCreditLimit()) : null);

        Period period = new Period(
                statement.getStartDate(),
                statement.getEndDate(),
                statement.getFirstTransactionDate(),
                statement.getLastTransactionDate(),
                statement.getGeneratedAt() == null ? LocalDateTime.now() : statement.getGeneratedAt());

        return new StatementModel(party, period, totals, rows, openingRow,
                // Shown only for a date-filtered statement; over the whole history the
                // opening balance is zero by definition and the row would be noise.
                statement.getStartDate() != null,
                lastPayment, lastSale);
    }

    private static String particularsFor(CustomerLedgerDTO entry) {
        String base = label(entry.getTransactionType());

        if (entry.getTransactionType() == TransactionType.SALE) {
            // Route and driver say which trip the birds came on - the two things a
            // customer asks about when they query a line.
            String trip = join(" / ", entry.getRouteName(), entry.getDriverName());
            return trip.isEmpty() ? base : base + " - " + trip;
        }
        if (entry.getTransactionType() == TransactionType.PAYMENT) {
            return isBlank(entry.getPaymentMode()) ? base : base + " (" + entry.getPaymentMode() + ")";
        }
        // Adjustments carry their reason in the description and nowhere else.
        return isBlank(entry.getDescription()) ? base : base + " - " + entry.getDescription();
    }

    /** INV-1042 - the reference a query can be traced by. */
    private static String voucherFor(CustomerLedgerDTO entry) {
        String prefix = voucherPrefix(entry.getTransactionType());
        return entry.getReferenceId() == null ? prefix : prefix + "-" + entry.getReferenceId();
    }

    private static String join(String separator, String... values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (isBlank(value)) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(separator);
            }
            joined.append(value.trim());
        }
        return joined.toString();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static BigDecimal zero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    public Party party() {
        return party;
    }

    public Period period() {
        return period;
    }

    public Totals totals() {
        return totals;
    }

    public List<Row> rows() {
        return rows;
    }

    public Row openingRow() {
        return openingRow;
    }

    public boolean showOpeningRow() {
        return showOpeningRow;
    }

    public LastEvent lastPayment() {
        return lastPayment;
    }

    public LastEvent lastSale() {
        return lastSale;
    }

    /** statement-javed-kureshi-2026-08-01_2026-09-11.pdf */
    public String fileName() {
        String name = party.name().isBlank() ? "customer" : party.name();
        String slug = name.replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-|-$", "")
                .toLowerCase();
        String periodPart = period.startDate() != null && period.endDate() != null
                ? period.startDate() + "_" + period.endDate()
                : "all";
        return "statement-" + (slug.isEmpty() ? "customer" : slug) + "-" + periodPart + ".pdf";
    }
}
