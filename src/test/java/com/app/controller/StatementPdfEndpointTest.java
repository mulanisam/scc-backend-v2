package com.app.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.app.service.LedgerService;
import com.app.service.report.StatementPdfService;

/**
 * The statement PDF response itself: content type, body, and the header that names the
 * file.
 *
 * The filename header earned its own test the hard way. ContentDisposition.inline()
 * .filename(name) returns a *builder*, so calling toString() on it - which compiles, and
 * looks right - sent "ContentDisposition$BuilderImpl@4e06d466" as the header value and
 * left the browser to invent a name for every statement downloaded. Nothing in the
 * renderer's own tests could have caught it; only reading the response could.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StatementPdfEndpointTest {

    private static final byte[] FAKE_PDF = "%PDF-1.4 not really a pdf".getBytes();
    private static final String FILE_NAME = "statement-javed-kureshi-2026-08-01_2026-09-08.pdf";

    @Mock
    private LedgerService ledgerService;

    @Mock
    private StatementPdfService statementPdfService;

    @InjectMocks
    private LedgerController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // standaloneSetup rather than @WebMvcTest: this asserts what the controller
        // writes, and loading the security filter chain to do it would only add a
        // reason for the test to fail for something unrelated.
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        when(statementPdfService.render(any(), any(), any()))
                .thenReturn(new StatementPdfService.Document(FAKE_PDF, FILE_NAME, null));
    }

    @Test
    @DisplayName("The response is a PDF named after the customer and the period")
    void respondsWithANamedPdf() throws Exception {
        mockMvc.perform(get("/user/ledger/customer/67/statement.pdf")
                        .param("startDate", "2026-08-01")
                        .param("endDate", "2026-09-08"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(FAKE_PDF))
                // The whole point: a real name, not a stringified builder.
                .andExpect(header().string("Content-Disposition",
                        "inline; filename=\"" + FILE_NAME + "\""))
                // The figures change with every sale entered, so a cached statement is
                // worse than a slow one.
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("The dates reach the service, and absent dates arrive as null rather than blank")
    void passesTheDateRangeThrough() throws Exception {
        mockMvc.perform(get("/user/ledger/customer/67/statement.pdf")
                        .param("startDate", "2026-08-01")
                        .param("endDate", "2026-09-08"))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(statementPdfService).render(
                eq(67L), eq(LocalDate.of(2026, 8, 1)), eq(LocalDate.of(2026, 9, 8)));

        mockMvc.perform(get("/user/ledger/customer/67/statement.pdf"))
                .andExpect(status().isOk());

        // Null, not LocalDate.EPOCH or today: the service reads null as "the whole
        // history", which is what suppresses the balance-brought-forward row.
        org.mockito.Mockito.verify(statementPdfService).render(eq(67L), isNull(), isNull());
    }
}
