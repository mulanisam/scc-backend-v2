package com.app.service.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.app.config.CompanyProperties;
import com.app.dto.CustomerLedgerDTO;
import com.app.dto.CustomerStatementDTO;
import com.app.dto.LedgerStatementTotals;
import com.app.dto.report.StatementModel;
import com.app.entity.CustomerLedger.TransactionType;

/**
 * The statement PDF, checked by reading it back.
 *
 * PDFBox can extract the text it wrote, which makes these real assertions rather than
 * "it produced some bytes": every figure a customer would dispute can be looked for on
 * the page. That matters most for the things the browser version got wrong before it was
 * rewritten - the raw enum name in the Particulars column, a balance with no side on it,
 * and totals that were computed separately from the ones on screen.
 */
class StatementPdfRendererTest {

    private final StatementPdfRenderer renderer = new StatementPdfRenderer(new CompanyProperties());

    // ---- fixtures ---------------------------------------------------------

    private CustomerLedgerDTO entry(long id, LocalDate date, TransactionType type,
                                    String debit, String credit, String balance) {
        CustomerLedgerDTO dto = new CustomerLedgerDTO();
        dto.setId(id);
        dto.setTransactionDate(date);
        dto.setTransactionType(type);
        dto.setReferenceId(id);
        dto.setDebitAmount(new BigDecimal(debit));
        dto.setCreditAmount(new BigDecimal(credit));
        dto.setRunningBalance(new BigDecimal(balance));
        return dto;
    }

    private CustomerStatementDTO statement(List<CustomerLedgerDTO> entries,
                                          LocalDate start, LocalDate end,
                                          String opening, String closing) {
        CustomerStatementDTO dto = new CustomerStatementDTO();
        dto.setCustomerId(67L);
        dto.setCustomerName("Javed Kureshi");
        dto.setShopName("Kureshi Chicken Centre");
        dto.setMobileNo("7798112855");
        dto.setAddress("Main Road");
        dto.setCityName("Madha");
        dto.setStartDate(start);
        dto.setEndDate(end);
        dto.setOpeningBalance(new BigDecimal(opening));
        dto.setEntries(entries);
        dto.setGeneratedAt(LocalDateTime.of(2026, 9, 11, 10, 30));

        if (!entries.isEmpty()) {
            dto.setFirstTransactionDate(entries.get(0).getTransactionDate());
            dto.setLastTransactionDate(entries.get(entries.size() - 1).getTransactionDate());
        }

        LedgerStatementTotals totals = new LedgerStatementTotals();
        totals.setRowCount(entries.size());
        totals.setSaleCount((int) entries.stream()
                .filter(e -> e.getTransactionType() == TransactionType.SALE).count());
        totals.setPaymentCount((int) entries.stream()
                .filter(e -> e.getTransactionType() == TransactionType.PAYMENT).count());
        totals.setTotalDebit(entries.stream().map(CustomerLedgerDTO::getDebitAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        totals.setTotalCredit(entries.stream().map(CustomerLedgerDTO::getCreditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        totals.setOpeningBalance(new BigDecimal(opening));
        totals.setClosingBalance(new BigDecimal(closing));
        totals.setNetMovement(totals.getTotalDebit().subtract(totals.getTotalCredit()));
        totals.setBirds(24L);
        totals.setWeight(new BigDecimal("61.500"));
        totals.setAverageRate(new BigDecimal("88.46"));
        dto.setTotals(totals);

        return dto;
    }

    private String textOf(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private int pageCountOf(byte[] pdf) throws IOException {
        try (PDDocument document = PDDocument.load(new ByteArrayInputStream(pdf))) {
            return document.getNumberOfPages();
        }
    }

    // ---- the whole document ----------------------------------------------

    @Test
    @DisplayName("A statement carries its identity, period, position and every transaction")
    void rendersAFullStatement() throws IOException {
        CustomerLedgerDTO sale = entry(1042L, LocalDate.of(2026, 9, 9),
                TransactionType.SALE, "5440.00", "4000.00", "307940.00");
        sale.setBirds(24);
        sale.setWeight(new BigDecimal("61.500"));
        sale.setRate(new BigDecimal("88.46"));
        sale.setRouteName("Madha");
        sale.setDriverName("Imran");

        CustomerLedgerDTO receipt = entry(88L, LocalDate.of(2026, 9, 10),
                TransactionType.PAYMENT, "0.00", "2000.00", "305940.00");
        receipt.setPaymentMode("CASH");

        byte[] pdf = renderer.render(StatementModel.of(statement(
                List.of(sale, receipt),
                LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11),
                "306500.00", "305940.00")));

        String text = textOf(pdf);

        assertTrue(text.contains("STATEMENT OF ACCOUNT"), text);
        assertTrue(text.contains("SOHEL CHICKEN CENTRE"), text);
        assertTrue(text.contains("Javed Kureshi"), text);
        assertTrue(text.contains("Kureshi Chicken Centre"), text);
        assertTrue(text.contains("CUS-67"), text);
        assertTrue(text.contains("01 Sep 2026"), text);
        assertTrue(text.contains("11 Sep 2026"), text);

        // The sale line, named the way a customer reads it, with its trip and voucher.
        assertTrue(text.contains("Sale - Madha / Imran"), text);
        assertTrue(text.contains("INV-1042"), text);
        assertTrue(text.contains("RCPT-88"), text);
        assertTrue(text.contains("Payment received (CASH)"), text);

        // The figures, grouped the Indian way.
        assertTrue(text.contains("5,440.00"), text);
        assertTrue(text.contains("61.500"), text);
        assertTrue(text.contains("88.46"), text);
        assertTrue(text.contains("3,05,940.00 Dr"), text);

        // A filtered statement always says where the balance came from.
        assertTrue(text.contains("Balance brought forward"), text);
        assertTrue(text.contains("3,06,500.00 Dr"), text);

        assertTrue(text.contains("Total for the period"), text);
        assertTrue(text.contains("AMOUNT PAYABLE, IN WORDS"), text);
        assertTrue(text.contains("Rupees Three Lakh Five Thousand Nine Hundred Forty only"), text);
        assertTrue(text.contains("Page 1 of 1"), text);
    }

    @Test
    @DisplayName("The raw enum name never reaches the page")
    void noEnumNamesOnThePage() throws IOException {
        // The defect this whole layout was rewritten to remove: the old PDF printed
        // "OPENING_BALANCE" in a Type column. An adjustment used to fall through the
        // frontend's lookup table and print "ADJUSTMENT" for the same reason.
        CustomerLedgerDTO adjustment = entry(9L, LocalDate.of(2026, 9, 5),
                TransactionType.ADJUSTMENT, "0.00", "500.00", "1000.00");
        adjustment.setDescription("Weight shortfall on trip 4149");

        byte[] pdf = renderer.render(StatementModel.of(statement(
                List.of(adjustment), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11),
                "1500.00", "1000.00")));

        String text = textOf(pdf);

        // The Particulars column is 45 mm, so this description wraps and the extracted
        // text carries a newline inside it. Asserted in the two pieces it actually
        // occupies rather than as one string - the wrap is correct behaviour, and the
        // point of the test is the wording, not the line break.
        assertTrue(text.contains("Adjustment - Weight shortfall on"), text);
        assertTrue(text.contains("trip 4149"), text);
        assertTrue(text.contains("ADJ-9"), text);
        assertFalse(text.contains("ADJUSTMENT"), text);
        assertFalse(text.contains("OPENING_BALANCE"), text);
        assertFalse(text.contains("CREDIT_NOTE"), text);
    }

    @Test
    @DisplayName("A credit balance reads as Cr and as an advance held, not as a negative")
    void creditBalanceStatesItsSide() throws IOException {
        CustomerLedgerDTO overpayment = entry(5L, LocalDate.of(2026, 9, 8),
                TransactionType.PAYMENT, "0.00", "3000.00", "-1500.00");

        byte[] pdf = renderer.render(StatementModel.of(statement(
                List.of(overpayment), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11),
                "1500.00", "-1500.00")));

        String text = textOf(pdf);

        assertTrue(text.contains("1,500.00 Cr"), text);
        assertTrue(text.contains("ADVANCE HELD, IN WORDS"), text);
        // The words state the amount; the band above says whose favour it is in, so a
        // minus sign in the words would be saying it twice and contradicting the band.
        assertTrue(text.contains("Rupees One Thousand Five Hundred only"), text);
        assertFalse(text.contains("-1,500.00"), text);
    }

    @Test
    @DisplayName("Over the whole history there is no brought-forward row to explain")
    void unfilteredStatementHasNoOpeningRow() throws IOException {
        CustomerLedgerDTO sale = entry(1L, LocalDate.of(2026, 9, 9),
                TransactionType.SALE, "1000.00", "0.00", "1000.00");

        byte[] pdf = renderer.render(StatementModel.of(statement(
                List.of(sale), null, null, "0.00", "1000.00")));

        String text = textOf(pdf);

        assertFalse(text.contains("Balance brought forward"), text);
        assertTrue(text.contains("All transactions"), text);
    }

    @Test
    @DisplayName("A long period description does not print through its own label")
    void longPeriodDescriptionKeepsClearOfItsLabel() throws IOException {
        // "All transactions  (09 Sep 2026  to  09 Sep 2026)" is wider than the gap
        // between the label and the right margin. Right-aligning it regardless drew it
        // over the label and produced "Statement pAerlilo tdransactions" on the page -
        // found by reading the text back out, which is why this test extracts rather
        // than just checking the bytes parse.
        CustomerLedgerDTO sale = entry(1L, LocalDate.of(2026, 9, 9),
                TransactionType.SALE, "1000.00", "0.00", "1000.00");

        String text = textOf(renderer.render(StatementModel.of(
                statement(List.of(sale), null, null, "0.00", "1000.00"))));

        assertTrue(text.contains("Statement period"),
                "The label must survive intact: " + text);
        assertTrue(text.contains("All transactions  (09 Sep 2026  to  09 Sep 2026)"),
                "And so must the value: " + text);
    }

    // ---- pagination ------------------------------------------------------

    @Test
    @DisplayName("A long statement paginates, and every page but the last carries the balance forward")
    void longStatementCarriesTheBalanceAcrossPages() throws IOException {
        List<CustomerLedgerDTO> entries = new ArrayList<>();
        BigDecimal balance = new BigDecimal("10000.00");
        for (int i = 1; i <= 120; i++) {
            balance = balance.add(new BigDecimal("500.00"));
            CustomerLedgerDTO sale = entry(i, LocalDate.of(2026, 6, 1).plusDays(i),
                    TransactionType.SALE, "500.00", "0.00", balance.toPlainString());
            sale.setBirds(10);
            sale.setWeight(new BigDecimal("25.000"));
            sale.setRate(new BigDecimal("20.00"));
            // Long enough to wrap the Particulars column, which is what makes the row
            // heights uneven and the page breaks impossible to predict by row count.
            sale.setRouteName("Madha Kurduwadi Pandharpur");
            sale.setDriverName("Imran Shaikh");
            entries.add(sale);
        }

        byte[] pdf = renderer.render(StatementModel.of(statement(
                entries, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 10, 1),
                "10000.00", balance.toPlainString())));

        int pages = pageCountOf(pdf);
        assertTrue(pages >= 3, "120 wrapped rows should not fit on two pages; got " + pages);

        String text = textOf(pdf);
        assertTrue(text.contains("Balance carried forward"), text);
        assertTrue(text.contains("Balance brought forward"), text);
        assertTrue(text.contains("Page 1 of " + pages), text);
        assertTrue(text.contains("Page " + pages + " of " + pages), text);

        // Every row is present: the first and the last, and the closing figure.
        assertTrue(text.contains("INV-1"), text);
        assertTrue(text.contains("INV-120"), text);
        assertTrue(text.contains("70,000.00 Dr"), text);
    }

    @Test
    @DisplayName("A statement with no transactions still states the position")
    void emptyStatementStillStatesThePosition() throws IOException {
        byte[] pdf = renderer.render(StatementModel.of(statement(
                List.of(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11),
                "0.00", "0.00")));

        String text = textOf(pdf);

        assertEquals(1, pageCountOf(pdf));
        assertTrue(text.contains("Total for the period"), text);
        assertTrue(text.contains("Rupees Nil"), text);
    }

    @Test
    @DisplayName("The filename names the customer and the period")
    void fileNameIdentifiesTheDocument() {
        StatementModel model = StatementModel.of(statement(
                List.of(), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11), "0.00", "0.00"));
        assertEquals("statement-javed-kureshi-2026-09-01_2026-09-11.pdf", model.fileName());

        StatementModel all = StatementModel.of(statement(List.of(), null, null, "0.00", "0.00"));
        assertEquals("statement-javed-kureshi-all.pdf", all.fileName());
    }

    @Test
    @DisplayName("Collection taken on the sale row is counted, so a payer is not shown as never paying")
    void collectionOnSaleRowsIsCounted() throws IOException {
        // Most money in this business arrives with the sale, not as a separate receipt.
        // A summary built on the receipt count alone told a customer who settles every
        // load at the door that they had made no payments.
        CustomerLedgerDTO first = entry(1L, LocalDate.of(2026, 9, 9),
                TransactionType.SALE, "5000.00", "5000.00", "0.00");
        CustomerLedgerDTO second = entry(2L, LocalDate.of(2026, 9, 10),
                TransactionType.SALE, "3000.00", "1000.00", "2000.00");

        StatementModel model = StatementModel.of(statement(
                List.of(first, second), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 11),
                "0.00", "2000.00"));

        assertEquals(2, model.totals().salesWithCollectionCount());
        assertEquals(new BigDecimal("6000.00"), model.totals().collectedWithSales());
        assertEquals(0, model.totals().paymentCount());

        String text = textOf(renderer.render(model));
        assertTrue(text.contains("Collected with sales"), text);
        assertTrue(text.contains("2 of 2"), text);
        assertTrue(text.contains("6,000.00"), text);
    }
}
