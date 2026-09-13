package com.app.service.report;

import java.awt.Color;
import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.stereotype.Component;

import com.app.config.CompanyProperties;
import com.app.dto.report.StatementModel;
import com.app.dto.report.StatementModel.Row;
import com.app.utility.StatementFormat;
import com.app.utility.pdf.PdfCanvas;

/**
 * Draws a customer statement of account.
 *
 * A port of frontend/src/components/ledger/ledgerStatementPdf.js, coordinate for
 * coordinate. That layout was worked out against real statements - identity and period
 * at the top, the position in four figures, one line per transaction with the balance
 * after each and the balance carried across page breaks, then a reconciliation that
 * adds up on the page - and there was no reason to redesign it, only to move it where
 * a scheduled job can reach it.
 *
 * Why it moved: the weekly WhatsApp statement has no browser to render in. Generating
 * here means the Download button and the Saturday job produce the same document, and a
 * customer who asks "is this the same as the one you sent me" gets a yes.
 *
 * The rupee sign: Helvetica is WinAnsi-encoded and has no glyph for U+20B9, so "Rs."
 * appears in headings and the money columns carry bare numbers - the same convention
 * the browser version settled on for the same reason.
 */
@Component
public class StatementPdfRenderer {

    // A4 portrait, millimetres.
    private static final float WIDTH = 210f;
    private static final float HEIGHT = 297f;
    private static final float MARGIN = 11f;
    private static final float CONTENT = WIDTH - MARGIN * 2;
    private static final float RIGHT = WIDTH - MARGIN;

    // One navy for structure, one grey for rules and secondary text, and a red and a
    // green used only where a figure's side has to read at a glance.
    private static final Color NAVY = new Color(31, 58, 95);
    private static final Color NAVY_SOFT = new Color(232, 237, 244);
    private static final Color TEXT = new Color(26, 26, 26);
    private static final Color MUTED = new Color(110, 118, 128);
    private static final Color RULE = new Color(190, 197, 205);
    private static final Color ZEBRA = new Color(248, 249, 251);
    private static final Color OPENING_FILL = new Color(241, 244, 248);
    private static final Color DEBIT = new Color(176, 42, 42);
    private static final Color CREDIT = new Color(27, 106, 58);
    private static final Color ON_NAVY = new Color(235, 240, 247);
    private static final Color WHITE = Color.WHITE;

    private static final PDFont REGULAR = PDType1Font.HELVETICA;
    private static final PDFont BOLD = PDType1Font.HELVETICA_BOLD;
    private static final PDFont ITALIC = PDType1Font.HELVETICA_OBLIQUE;

    /**
     * The statement's columns, in the order a reader scans them: what happened, then
     * what it was made of, then what it did to the balance.
     *
     * Widths are millimetres and total 188 - the printable width at an 11 mm margin.
     * Fixed rather than fitted to content, so every page of a long statement and every
     * statement in a stack of them has its columns in the same place.
     */
    private record Column(String label, float width, boolean numeric) {
    }

    private static final List<Column> COLUMNS = List.of(
            new Column("Date", 19f, false),
            new Column("Particulars", 45f, false),
            new Column("Voucher", 15f, false),
            new Column("Birds", 11f, true),
            new Column("Weight (kg)", 18f, true),
            new Column("Rate/kg", 13f, true),
            new Column("Debit (Rs.)", 21f, true),
            new Column("Credit (Rs.)", 21f, true),
            new Column("Balance (Rs.)", 25f, true));

    /** Clear space a value keeps from the label sharing its line. */
    private static final float LABEL_GAP = 3f;

    private static final float CELL_PAD = 2f;
    private static final float BODY_FONT = 8f;
    private static final float HEAD_FONT = 7.8f;
    private static final float LINE_HEIGHT = 3.4f;
    private static final float ROW_PAD = 1.7f;

    /** Room kept at the foot of a page for the carried-forward line and the footer. */
    private static final float BOTTOM_LIMIT = HEIGHT - 22f;
    /** Where rows start on a continuation page, below the compact masthead. */
    private static final float CONTINUATION_TOP = 32f;

    private static final DateTimeFormatter GENERATED_AT =
            DateTimeFormatter.ofPattern("dd/MM/yyyy, hh:mm:ss a");

    private final CompanyProperties company;

    public StatementPdfRenderer(CompanyProperties company) {
        this.company = company;
    }

    /** The finished PDF. */
    public byte[] render(StatementModel model) {
        try (PdfCanvas canvas = new PdfCanvas(WIDTH, HEIGHT)) {
            float y = drawMasthead(canvas);
            y = drawPartyBlock(canvas, model, y);
            y = drawPositionStrip(canvas, model, y);
            y = drawTransactions(canvas, model, y);
            y = drawClosing(canvas, model, y);
            drawFootMatter(canvas, model, y);
            return canvas.toByteArray();
        }
    }

    // ---- heading ----------------------------------------------------------

    /** Company block and statement title. Returns the y to continue from. */
    private float drawMasthead(PdfCanvas canvas) {
        canvas.font(BOLD, 17f);
        canvas.color(NAVY);
        canvas.text(company.getName(), MARGIN, MARGIN + 6);

        canvas.font(REGULAR, 8.5f);
        canvas.color(MUTED);
        canvas.text(company.getAddress(), MARGIN, MARGIN + 11.5f);
        canvas.text("Phone " + company.getContactNumber() + "   |   " + company.getEmail(),
                MARGIN, MARGIN + 16);

        // The title sits opposite the company name, where a reader looks to find out
        // what the document is before reading any of it.
        canvas.font(BOLD, 12.5f);
        canvas.color(NAVY);
        canvas.textRight("STATEMENT OF ACCOUNT", RIGHT, MARGIN + 6);

        canvas.line(MARGIN, MARGIN + 19.5f, RIGHT, MARGIN + 19.5f, NAVY, 0.7f);
        return MARGIN + 19.5f;
    }

    /**
     * Customer identity on the left, statement metadata on the right, as two aligned
     * label/value stacks: label at the midpoint, value flush right, so the values form
     * their own column instead of trailing off after the labels.
     */
    private float drawPartyBlock(PdfCanvas canvas, StatementModel model, float y) {
        float midpoint = MARGIN + CONTENT * 0.56f;
        StatementModel.Party party = model.party();

        canvas.font(BOLD, 7.5f);
        canvas.color(MUTED);
        canvas.text("STATEMENT FOR", MARGIN, y + 6);
        canvas.text("STATEMENT DETAILS", midpoint, y + 6);

        canvas.font(BOLD, 12f);
        canvas.color(TEXT);
        canvas.text(party.name(), MARGIN, y + 12);

        canvas.font(REGULAR, 9f);
        canvas.color(MUTED);
        float leftY = y + 17;
        for (String line : List.of(
                party.shopName(),
                joinNonBlank(", ", party.address(), party.cityName()),
                party.mobileNo().isBlank() ? "" : "Mobile " + party.mobileNo())) {
            if (line.isBlank()) {
                continue;
            }
            canvas.text(line, MARGIN, leftY);
            leftY += 4.6f;
        }

        List<String[]> details = new ArrayList<>();
        details.add(new String[] { "Account no.",
                "CUS-" + (party.id() == null ? "-" : party.id()) });
        details.add(new String[] { "Statement period", model.period().describe() });
        details.add(new String[] { "Transactions", String.valueOf(model.totals().rowCount()) });
        details.add(new String[] { "Generated on",
                model.period().generatedAt().format(GENERATED_AT) });
        if (model.lastPayment() != null) {
            details.add(new String[] { "Last payment received",
                    StatementFormat.date(model.lastPayment().date())
                            + "  -  Rs. " + StatementFormat.money(model.lastPayment().amount()) });
        }
        if (party.creditLimit() != null) {
            details.add(new String[] { "Credit limit",
                    "Rs. " + StatementFormat.money(party.creditLimit()) });
        }
        if (party.obsolete()) {
            details.add(new String[] { "Account status", "Inactive" });
        }

        /*
         * Label left, value right - unless the value is too wide to share the line.
         *
         * "All transactions  (09 Sep 2026  to  09 Sep 2026)" is 47 characters and the
         * gap between the label and the right margin is not wide enough for it, so
         * right-aligning it unconditionally printed it straight through its own label:
         * "Statement pAerlilo tdransactions". Dropping it to its own line keeps both
         * legible and keeps the values in one right-aligned column.
         */
        float rightY = y + 12;
        for (String[] detail : details) {
            canvas.font(REGULAR, 8.5f);
            canvas.color(MUTED);
            canvas.text(detail[0], midpoint, rightY);
            float labelWidth = canvas.widthOf(detail[0]);

            canvas.font(BOLD, 8.5f);
            canvas.color(TEXT);
            if (canvas.widthOf(detail[1]) > RIGHT - midpoint - labelWidth - LABEL_GAP) {
                rightY += 4.2f;
            }
            canvas.textRight(detail[1], RIGHT, rightY);
            rightY += 4.8f;
        }

        return Math.max(leftY, rightY) + 1;
    }

    /**
     * The position in four figures, before any transaction is read: what was owed at
     * the start, what was billed, what was received, what is owed now.
     */
    private float drawPositionStrip(PdfCanvas canvas, StatementModel model, float y) {
        StatementModel.Totals totals = model.totals();
        String[][] boxes = {
            { "Opening balance", StatementFormat.balance(totals.openingBalance()), "" },
            { "Billed in period (Dr)", StatementFormat.money(totals.totalDebit()), "" },
            { "Received in period (Cr)", StatementFormat.money(totals.totalCredit()), "" },
            { "Closing balance", StatementFormat.balance(totals.closingBalance()), "emphasis" }
        };

        float gap = 3f;
        float boxWidth = (CONTENT - gap * (boxes.length - 1)) / boxes.length;
        float boxHeight = 16f;

        for (int index = 0; index < boxes.length; index++) {
            boolean emphasis = !boxes[index][2].isEmpty();
            float x = MARGIN + index * (boxWidth + gap);

            canvas.fillRect(x, y, boxWidth, boxHeight, emphasis ? NAVY : NAVY_SOFT);

            canvas.font(REGULAR, 7.2f);
            canvas.color(emphasis ? ON_NAVY : MUTED);
            canvas.text(boxes[index][0].toUpperCase(), x + 2.5f, y + 5.5f);

            canvas.font(BOLD, 11f);
            canvas.color(emphasis ? WHITE : TEXT);
            canvas.textRight(boxes[index][1], x + boxWidth - 2.5f, y + 12.5f);
        }

        return y + boxHeight + 4;
    }

    // ---- the transaction table -------------------------------------------

    /** One row's nine cells, already worded. */
    private String[] cells(Row row) {
        String particulars = row.particulars()
                + (row.backdated() ? "  [back-dated]" : "")
                + (row.obsolete() ? "  [corrected]" : "");
        return new String[] {
            StatementFormat.date(row.date()),
            particulars,
            row.voucher(),
            StatementFormat.count(row.birds()),
            StatementFormat.weight(row.weight()),
            StatementFormat.rate(row.rate()),
            row.hasDebit() ? StatementFormat.money(row.debit()) : "",
            row.hasCredit() ? StatementFormat.money(row.credit()) : "",
            StatementFormat.balance(row.balance())
        };
    }

    /**
     * The table, and the two things that make a multi-page statement readable: the
     * balance brought forward at the top of each continuation page, and the balance
     * carried forward at the foot of every page but the last.
     *
     * Paginated by measuring as it goes rather than by a row count, because the
     * Particulars column wraps: a sale with a long route and driver takes two lines and
     * a payment takes one, so where a page ends cannot be known in advance.
     */
    private float drawTransactions(PdfCanvas canvas, StatementModel model, float startY) {
        List<Row> bodyRows = new ArrayList<>();
        if (model.showOpeningRow()) {
            bodyRows.add(model.openingRow());
        }
        bodyRows.addAll(model.rows());

        // What each page ends on, recorded as the table is drawn and used afterwards,
        // once the page count is known, to place the carry-forward lines.
        List<float[]> pageFoot = new ArrayList<>();
        List<BigDecimal> pageCarried = new ArrayList<>();

        float y = drawTableHead(canvas, startY);
        BigDecimal carried = model.showOpeningRow()
                ? model.openingRow().balance()
                : BigDecimal.ZERO;
        boolean zebra = false;
        int pageIndex = canvas.currentPageIndex();

        for (int index = 0; index < bodyRows.size(); index++) {
            Row row = bodyRows.get(index);
            boolean isOpeningRow = model.showOpeningRow() && index == 0;

            canvas.font(REGULAR, BODY_FONT);
            List<String> particularsLines = canvas.wrap(
                    cells(row)[1], COLUMNS.get(1).width() - CELL_PAD * 2);
            float rowHeight = ROW_PAD * 2 + Math.max(1, particularsLines.size()) * LINE_HEIGHT;

            if (y + rowHeight > BOTTOM_LIMIT) {
                pageFoot.add(new float[] { y });
                pageCarried.add(carried);
                canvas.addPage();
                pageIndex = canvas.currentPageIndex();
                drawContinuationMasthead(canvas, model, carried);
                y = drawTableHead(canvas, CONTINUATION_TOP);
                zebra = false;
            }

            drawBodyRow(canvas, row, particularsLines, y, rowHeight, zebra, isOpeningRow);
            y += rowHeight;
            zebra = !zebra;
            carried = row.balance();
        }

        // Period totals as the table's own last row, so debits and credits are read in
        // the columns they belong to rather than restated in prose underneath.
        float totalsHeight = ROW_PAD * 2 + LINE_HEIGHT;
        if (y + totalsHeight > BOTTOM_LIMIT) {
            pageFoot.add(new float[] { y });
            pageCarried.add(carried);
            canvas.addPage();
            pageIndex = canvas.currentPageIndex();
            drawContinuationMasthead(canvas, model, carried);
            y = drawTableHead(canvas, CONTINUATION_TOP);
        }
        y = drawTotalsRow(canvas, model, y, totalsHeight);

        // Carried-forward at the foot of each page that has a page after it. Written now
        // that the page count is settled; the last page ends with the totals row and a
        // "carried forward" there would be wrong.
        int lastPage = canvas.currentPageIndex();
        for (int page = 0; page < pageFoot.size(); page++) {
            canvas.setPage(page);
            canvas.font(BOLD, 8.5f);
            canvas.color(TEXT);
            float footY = pageFoot.get(page)[0] + 5;
            canvas.text("Balance carried forward", MARGIN, footY);
            canvas.textRight(StatementFormat.balance(pageCarried.get(page)), RIGHT, footY);
        }
        canvas.setPage(lastPage);
        // pageIndex is tracked for the reader's benefit and asserted by the tests; the
        // renderer itself always ends on the last page.
        assert pageIndex == lastPage;

        return y;
    }

    private float drawTableHead(PdfCanvas canvas, float y) {
        float height = 2.2f * 2 + LINE_HEIGHT;
        canvas.fillRect(MARGIN, y, CONTENT, height, NAVY);

        canvas.font(BOLD, HEAD_FONT);
        canvas.color(WHITE);
        float x = MARGIN;
        for (Column column : COLUMNS) {
            float baseline = y + 2.2f + LINE_HEIGHT - 0.9f;
            if (column.numeric()) {
                canvas.textRight(column.label(), x + column.width() - CELL_PAD, baseline);
            } else {
                canvas.text(column.label(), x + CELL_PAD, baseline);
            }
            x += column.width();
        }
        return y + height;
    }

    private void drawBodyRow(PdfCanvas canvas, Row row, List<String> particularsLines,
                             float y, float height, boolean zebra, boolean isOpeningRow) {
        if (isOpeningRow) {
            canvas.fillRect(MARGIN, y, CONTENT, height, OPENING_FILL);
        } else if (zebra) {
            canvas.fillRect(MARGIN, y, CONTENT, height, ZEBRA);
        }

        String[] values = cells(row);
        PDFont face = row.obsolete() ? ITALIC : (isOpeningRow ? BOLD : REGULAR);
        float firstBaseline = y + ROW_PAD + LINE_HEIGHT - 0.9f;
        float x = MARGIN;

        for (int col = 0; col < COLUMNS.size(); col++) {
            Column column = COLUMNS.get(col);

            /*
             * Debits and credits keep their sides' colour and the balance follows whether
             * the account is in debit or credit; everything else stays black so the page
             * does not turn into a colour chart. A superseded row is grey and italic
             * throughout - it is shown for trace, and colouring its figures would give
             * them the same weight as the ones that count.
             */
            Color ink = TEXT;
            PDFont cellFace = face;
            if (row.obsolete()) {
                ink = MUTED;
            } else if (col == 6 && row.hasDebit()) {
                ink = DEBIT;
            } else if (col == 7 && row.hasCredit()) {
                ink = CREDIT;
            } else if (col == 8) {
                cellFace = BOLD;
                int side = row.balance().signum();
                ink = side > 0 ? DEBIT : side < 0 ? CREDIT : TEXT;
            }

            canvas.font(cellFace, BODY_FONT);
            canvas.color(ink);

            if (col == 1) {
                float lineY = firstBaseline;
                for (String line : particularsLines) {
                    canvas.text(line, x + CELL_PAD, lineY);
                    lineY += LINE_HEIGHT;
                }
            } else if (column.numeric()) {
                canvas.textRight(values[col], x + column.width() - CELL_PAD, firstBaseline);
            } else {
                canvas.text(values[col], x + CELL_PAD, firstBaseline);
            }
            x += column.width();
        }

        canvas.line(MARGIN, y + height, MARGIN + CONTENT, y + height, RULE, 0.1f);
    }

    private float drawTotalsRow(PdfCanvas canvas, StatementModel model, float y, float height) {
        StatementModel.Totals totals = model.totals();
        String[] values = {
            "",
            "Total for the period",
            "",
            StatementFormat.count(totals.birds()),
            StatementFormat.weight(totals.weight()),
            StatementFormat.rate(totals.averageRate()),
            StatementFormat.money(totals.totalDebit()),
            StatementFormat.money(totals.totalCredit()),
            StatementFormat.balance(totals.closingBalance())
        };

        canvas.fillRect(MARGIN, y, CONTENT, height, NAVY_SOFT);
        canvas.line(MARGIN, y, MARGIN + CONTENT, y, NAVY, 0.4f);
        canvas.line(MARGIN, y + height, MARGIN + CONTENT, y + height, NAVY, 0.4f);

        canvas.font(BOLD, BODY_FONT);
        canvas.color(NAVY);
        float baseline = y + ROW_PAD + LINE_HEIGHT - 0.9f;
        float x = MARGIN;
        for (int col = 0; col < COLUMNS.size(); col++) {
            Column column = COLUMNS.get(col);
            if (column.numeric()) {
                canvas.textRight(values[col], x + column.width() - CELL_PAD, baseline);
            } else {
                canvas.text(values[col], x + CELL_PAD, baseline);
            }
            x += column.width();
        }
        return y + height;
    }

    /**
     * A compact masthead on continuation pages, so a loose sheet still says whose
     * account it is, then the balance the page opens on.
     */
    private void drawContinuationMasthead(PdfCanvas canvas, StatementModel model,
                                          BigDecimal broughtForward) {
        canvas.font(BOLD, 10f);
        canvas.color(NAVY);
        canvas.text(company.getName(), MARGIN, MARGIN + 4);

        canvas.font(REGULAR, 8.5f);
        canvas.color(MUTED);
        String who = "Statement of account - " + model.party().name()
                + (model.party().shopName().isBlank() ? "" : ", " + model.party().shopName());
        canvas.text(who, MARGIN, MARGIN + 9);
        canvas.textRight(model.period().describe(), RIGHT, MARGIN + 9);

        canvas.font(BOLD, 8.5f);
        canvas.color(TEXT);
        canvas.text("Balance brought forward", MARGIN, MARGIN + 17.5f);
        canvas.textRight(StatementFormat.balance(broughtForward), RIGHT, MARGIN + 17.5f);
        canvas.line(MARGIN, MARGIN + 19.5f, RIGHT, MARGIN + 19.5f, RULE, 0.2f);
    }

    // ---- closing ----------------------------------------------------------

    /**
     * What was traded and how the balance moved, side by side, then the amount in
     * words. The reconciliation is arithmetic a reader can follow down the page:
     * opening, plus billed, less received, equals closing.
     */
    private float drawClosing(PdfCanvas canvas, StatementModel model, float y) {
        StatementModel.Totals totals = model.totals();
        float blockWidth = (CONTENT - 6) / 2;
        float rightX = MARGIN + blockWidth + 6;

        // A new page rather than a summary split across the break.
        float top = y + 8;
        if (top + 62 > HEIGHT - 20) {
            canvas.addPage();
            top = MARGIN + 8;
        }

        canvas.font(BOLD, 7.5f);
        canvas.color(MUTED);
        canvas.text("WHAT WAS TRADED", MARGIN, top);
        canvas.text("HOW THE BALANCE MOVED", rightX, top);

        String saleCount = orZero(StatementFormat.count(totals.saleCount()), "0");
        String[][] traded = {
            { "Sale transactions", saleCount },
            { "Birds supplied", orZero(StatementFormat.count(totals.birds()), "0") },
            { "Weight supplied (kg)", orZero(StatementFormat.weight(totals.weight()), "0.000") },
            { "Average realised rate (Rs./kg)", orZero(StatementFormat.rate(totals.averageRate()), "0.00") },
            // Two ways money comes in, kept apart because most of it arrives on the sale
            // row itself and a payment-voucher count alone reads as "never paid".
            { "Collected with sales",
              orZero(StatementFormat.count(totals.salesWithCollectionCount()), "0")
                      + " of " + saleCount
                      + "  -  Rs. " + StatementFormat.money(totals.collectedWithSales()) },
            { "Separate payment receipts", orZero(StatementFormat.count(totals.paymentCount()), "0") },
            { "Adjustments (credit / debit notes)",
              orZero(StatementFormat.count(totals.adjustmentCount()), "0") }
        };

        float leftY = top + 6;
        for (String[] line : traded) {
            canvas.font(REGULAR, 9f);
            canvas.color(MUTED);
            canvas.text(line[0], MARGIN, leftY);
            canvas.font(BOLD, 9f);
            canvas.color(TEXT);
            canvas.textRight(line[1], MARGIN + blockWidth, leftY);
            leftY += 5.2f;
        }

        String[][] movement = {
            { "Opening balance", StatementFormat.balance(totals.openingBalance()), "" },
            { "Add: sales and debits in period", "+ " + StatementFormat.money(totals.totalDebit()), "" },
            { "Less: payments and credits in period", "- " + StatementFormat.money(totals.totalCredit()), "" },
            { "Closing balance", StatementFormat.balance(totals.closingBalance()), "total" }
        };

        float rightY = top + 6;
        for (int index = 0; index < movement.length; index++) {
            boolean isTotal = !movement[index][2].isEmpty();
            if (isTotal) {
                canvas.line(rightX, rightY - 3.6f, RIGHT, rightY - 3.6f, NAVY, 0.3f);
                rightY += 1.4f;
            }
            canvas.font(isTotal ? BOLD : REGULAR, isTotal ? 10.5f : 9f);
            canvas.color(isTotal ? NAVY : MUTED);
            canvas.text(movement[index][0], rightX, rightY);

            int side = totals.closingBalance().signum();
            canvas.font(BOLD, isTotal ? 10.5f : 9f);
            canvas.color(isTotal ? (side > 0 ? DEBIT : side < 0 ? CREDIT : TEXT) : TEXT);
            canvas.textRight(movement[index][1], RIGHT, rightY);

            rightY += index == movement.length - 2 ? 6f : 5.2f;
        }

        float bottom = Math.max(leftY, rightY) + 2;

        // The amount, in words, on its own band - the line a customer reads first and
        // the one a dispute is settled against.
        canvas.fillRect(MARGIN, bottom, CONTENT, 13f, NAVY_SOFT);
        canvas.font(REGULAR, 7.5f);
        canvas.color(MUTED);
        canvas.text(totals.closingBalance().signum() < 0
                        ? "ADVANCE HELD, IN WORDS" : "AMOUNT PAYABLE, IN WORDS",
                MARGIN + 3, bottom + 4.5f);
        canvas.font(BOLD, 9.5f);
        canvas.color(TEXT);
        canvas.text(StatementFormat.amountInWords(totals.closingBalance()), MARGIN + 3, bottom + 10);

        return bottom + 13;
    }

    /** Notes and signature, then the footer on every page. */
    private void drawFootMatter(PdfCanvas canvas, StatementModel model, float y) {
        float top = y + 8;
        if (top + 26 > HEIGHT - 18) {
            canvas.addPage();
            top = MARGIN + 8;
        }

        canvas.font(REGULAR, 7.8f);
        canvas.color(MUTED);
        String[] notes = {
            "Dr means the amount is owed to us; Cr means the account is in credit.",
            "Rows marked [back-dated] were entered after a later transaction; "
                    + "[corrected] rows have been superseded and are shown for trace only.",
            "Please verify this statement and report any discrepancy within 7 days."
        };
        for (int index = 0; index < notes.length; index++) {
            canvas.text(notes[index], MARGIN, top + index * 4);
        }

        canvas.font(REGULAR, 8.5f);
        canvas.color(MUTED);
        canvas.textRight("For " + company.getName(), RIGHT, top);
        canvas.line(RIGHT - 50, top + 14, RIGHT, top + 14, RULE, 0.2f);
        canvas.font(REGULAR, 7.8f);
        canvas.textRight("Authorised signatory", RIGHT, top + 18);

        // Footer last, on every page, once the count is final.
        int pageCount = canvas.pageCount();
        for (int page = 0; page < pageCount; page++) {
            canvas.setPage(page);
            canvas.line(MARGIN, HEIGHT - 13, RIGHT, HEIGHT - 13, RULE, 0.2f);

            canvas.font(REGULAR, 7.2f);
            canvas.color(MUTED);
            canvas.text(company.getName() + " - " + company.getWebsite(), MARGIN, HEIGHT - 9);
            canvas.textCentre("Statement of account - " + model.party().name(),
                    WIDTH / 2, HEIGHT - 9);
            canvas.textRight("Page " + (page + 1) + " of " + pageCount, RIGHT, HEIGHT - 9);
            canvas.text("Computer-generated statement; no signature required for verification.",
                    MARGIN, HEIGHT - 5.5f);
        }
    }

    private static String orZero(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String joinNonBlank(String separator, String... values) {
        StringBuilder joined = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append(separator);
            }
            joined.append(value.trim());
        }
        return joined.toString();
    }
}
