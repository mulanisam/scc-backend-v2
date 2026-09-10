package com.app.utility;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Guards the agreement between the browser's rounding rule and this one.
 *
 * The server now rejects a bulk entry whose submitted amount disagrees with what
 * it calculates, so any divergence between the two implementations would reject
 * legitimate sales. frontend-rounding-cases.txt holds amounts produced by the
 * frontend's businessRules.calculateAmount for a spread of realistic weights and
 * rates; this test asserts the Java rule reproduces every one of them.
 *
 * Regenerate the fixture if the frontend rule ever changes deliberately - a
 * failure here means the two sides have drifted apart.
 */
class FrontendRoundingParityTest {

    private record Case(String kilograms, String rate, String expectedAmount) {
    }

    private static List<Case> loadCases() throws Exception {
        List<Case> cases = new ArrayList<>();
        try (InputStream in = FrontendRoundingParityTest.class.getClassLoader()
                .getResourceAsStream("frontend-rounding-cases.txt")) {

            if (in == null) {
                throw new IllegalStateException("frontend-rounding-cases.txt is missing from test resources");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] parts = line.trim().split("\\|");
                    cases.add(new Case(parts[0], parts[1], parts[2]));
                }
            }
        }
        return cases;
    }

    @Test
    @DisplayName("Java rounding matches the frontend for every recorded case")
    void matchesFrontendForEveryCase() throws Exception {
        List<Case> cases = loadCases();
        assertTrue(cases.size() >= 100, "expected a meaningful number of cases, got " + cases.size());

        List<String> mismatches = new ArrayList<>();
        for (Case testCase : cases) {
            BigDecimal actual = MoneyRules.calculateAmount(
                    new BigDecimal(testCase.kilograms()), new BigDecimal(testCase.rate()));

            if (new BigDecimal(testCase.expectedAmount()).compareTo(actual) != 0) {
                mismatches.add(String.format("%s kg at %s: frontend %s, server %s",
                        testCase.kilograms(), testCase.rate(), testCase.expectedAmount(),
                        actual.toPlainString()));
            }
        }

        assertEquals(List.of(), mismatches,
                () -> "Rounding diverged from the frontend in " + mismatches.size() + " case(s)");
    }
}
