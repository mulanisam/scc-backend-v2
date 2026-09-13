package com.app.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the agreement between the statement the browser draws and the one the server
 * draws.
 *
 * They are now two implementations of the same document: the Download button renders in
 * the browser, the weekly WhatsApp job renders here. A customer comparing the two would
 * find any divergence immediately, and a figure that differed between them is the
 * hardest kind of discrepancy to explain - so every formatting rule is asserted against
 * the same fixture, generated from the JavaScript that customers have been reading.
 *
 * A failure here means the two sides have drifted. Regenerate the fixture only when the
 * browser rule has been changed deliberately.
 */
class StatementFormatParityTest {

    private record Case(String function, String input, String expected) {
    }

    private static List<Case> loadCases() throws Exception {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = StatementFormatParityTest.class.getClassLoader()
                .getResourceAsStream("statement-format-cases.txt")) {

            if (in == null) {
                throw new IllegalStateException(
                        "statement-format-cases.txt is missing from test resources");
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                // Split on the first two pipes only: an expected value can contain one,
                // and the trailing spaces on a zero balance are significant.
                int first = line.indexOf('|');
                int second = line.indexOf('|', first + 1);
                cases.add(new Case(
                        line.substring(0, first),
                        line.substring(first + 1, second),
                        line.substring(second + 1)));
            }
        }
        return cases;
    }

    private static String apply(Case testCase) {
        String input = testCase.input();
        return switch (testCase.function()) {
            case "money" -> StatementFormat.money(new BigDecimal(input));
            case "balance" -> StatementFormat.balance(new BigDecimal(input));
            case "weight" -> StatementFormat.weight(new BigDecimal(input));
            case "rate" -> StatementFormat.rate(new BigDecimal(input));
            case "count" -> StatementFormat.count(Long.parseLong(input));
            case "date" -> StatementFormat.date(LocalDate.parse(input));
            case "words" -> StatementFormat.amountInWords(new BigDecimal(input));
            default -> throw new IllegalStateException(
                    "The fixture names a function this test does not know: " + testCase.function());
        };
    }

    @Test
    @DisplayName("Every formatting rule produces the string the browser produces")
    void matchesTheBrowserCharacterForCharacter() throws Exception {
        List<Case> cases = loadCases();
        assertTrue(cases.size() >= 60,
                "The fixture should cover every rule; it has only " + cases.size() + " cases");

        List<String> mismatches = new ArrayList<>();
        for (Case testCase : cases) {
            String actual = apply(testCase);
            if (!testCase.expected().equals(actual)) {
                mismatches.add(String.format("%s(%s): browser \"%s\", server \"%s\"",
                        testCase.function(), testCase.input(), testCase.expected(), actual));
            }
        }

        assertTrue(mismatches.isEmpty(),
                "The two statement renderings have drifted apart:\n  "
                        + String.join("\n  ", mismatches));
    }

    @Test
    @DisplayName("The fixture actually covers all seven rules")
    void fixtureCoversEveryRule() throws Exception {
        List<String> functions = loadCases().stream().map(Case::function).distinct().toList();
        for (String rule : List.of("money", "balance", "weight", "rate", "count", "date", "words")) {
            assertTrue(functions.contains(rule),
                    "No " + rule + " cases in the fixture, so that rule is unguarded");
        }
    }

    // ---- the rules worth stating outright ---------------------------------

    @Test
    @DisplayName("Indian grouping: the last three digits, then pairs")
    void indianDigitGrouping() {
        // Not a locale lookup. An en-IN locale missing from the JVM would fall back to
        // 1,234,567 - a number nobody here reads without effort - and it would do so
        // silently, on statements already posted.
        assertEquals("1,000.00", StatementFormat.money(new BigDecimal("1000")));
        assertEquals("1,23,456.00", StatementFormat.money(new BigDecimal("123456")));
        assertEquals("12,34,567.00", StatementFormat.money(new BigDecimal("1234567")));
        assertEquals("1,23,45,67,890.00", StatementFormat.money(new BigDecimal("1234567890")));
    }

    @Test
    @DisplayName("A balance states its side, never a minus sign")
    void balanceStatesItsSide() {
        // Dr and Cr are how a customer reads a statement from anyone else, and a bare
        // negative leaves open the question of whose favour it is in.
        assertEquals("1,500.00 Dr", StatementFormat.balance(new BigDecimal("1500")));
        assertEquals("1,500.00 Cr", StatementFormat.balance(new BigDecimal("-1500")));
        assertFalse(StatementFormat.balance(new BigDecimal("-1500")).contains("-"));

        // Zero keeps trailing spaces so the column still lines up under Dr and Cr.
        assertEquals("0.00    ", StatementFormat.balance(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("September is Sep, three characters like every other month")
    void septemberIsThreeCharacters() {
        // toLocaleDateString('en-IN') gives "Sept" - eleven characters for every month
        // and twelve for one, which is exactly the misalignment the date column has to
        // avoid in a fixed-width layout.
        String september = StatementFormat.date(LocalDate.of(2026, 9, 9));
        assertEquals("09 Sep 2026", september);
        assertEquals(11, september.length());

        for (int month = 1; month <= 12; month++) {
            assertEquals(11, StatementFormat.date(LocalDate.of(2026, month, 1)).length(),
                    "Month " + month + " must format to the same width as the others");
        }
    }

    @Test
    @DisplayName("Zero quantities are blank, because an empty cell reads better than 0.000")
    void zeroQuantitiesAreBlank() {
        assertEquals("", StatementFormat.weight(BigDecimal.ZERO));
        assertEquals("", StatementFormat.rate(BigDecimal.ZERO));
        assertEquals("", StatementFormat.count(0));
        // But a balance of zero is a fact worth stating.
        assertEquals("0.00    ", StatementFormat.balance(BigDecimal.ZERO));
        assertEquals("0.00", StatementFormat.money(BigDecimal.ZERO));
    }

    @Test
    @DisplayName("Paise with no rupees behind them do not print a dangling \"Rupees\"")
    void paiseAloneReadsCorrectly() {
        assertEquals("Fifty Paise only", StatementFormat.amountInWords(new BigDecimal("0.50")));
        assertEquals("Rupees Nil", StatementFormat.amountInWords(BigDecimal.ZERO));
        assertEquals("Rupees One Thousand Five Hundred and Twenty Five Paise only",
                StatementFormat.amountInWords(new BigDecimal("1500.25")));
    }

    @Test
    @DisplayName("Amounts in words, carried over from the browser's own test cases")
    void amountInWordsMatchesTheBrowsersCases() {
        // These four came from ledgerStatementPdf.test.js, which was deleted when the
        // PDF moved here. Kept verbatim so the behaviour customers have been reading on
        // their statements is still asserted somewhere.
        assertEquals("Rupees Fifteen Thousand Five Hundred only",
                StatementFormat.amountInWords(new BigDecimal("15500")));
        // The whole receivables book, as it stood when this was written.
        assertEquals("Rupees Two Crore Three Lakh Sixty Seven Thousand Two Hundred Forty Seven only",
                StatementFormat.amountInWords(new BigDecimal("20367247")));
        assertEquals("Rupees One Thousand Two Hundred Fifty and Seventy Five Paise only",
                StatementFormat.amountInWords(new BigDecimal("1250.75")));
        // A credit balance reads as its magnitude; the band above the words says which
        // way round it is.
        assertEquals("Rupees Five Hundred only",
                StatementFormat.amountInWords(new BigDecimal("-500")));
    }

    @Test
    @DisplayName("Null is treated as zero rather than throwing mid-render")
    void nullsDoNotBreakARender() {
        // Weight, rate and birds are all nullable on a ledger row, and a statement that
        // fails to render is worse than one with a blank cell.
        assertEquals("0.00", StatementFormat.money(null));
        assertEquals("0.00    ", StatementFormat.balance(null));
        assertEquals("", StatementFormat.weight(null));
        assertEquals("", StatementFormat.rate(null));
        assertEquals("", StatementFormat.date(null));
        assertEquals("Rupees Nil", StatementFormat.amountInWords(null));
    }
}
