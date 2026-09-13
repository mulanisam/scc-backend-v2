package com.app.service.report;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.CustomerStatementDTO;
import com.app.dto.report.StatementModel;
import com.app.service.LedgerService;

/**
 * Produces a customer statement PDF.
 *
 * The one place that turns a customer and a date range into a document, so the Download
 * button, the weekly WhatsApp job and anything added later all send the identical file.
 * Previously the layout lived in the browser, which meant a scheduled send had no way to
 * produce one at all.
 */
@Service
public class StatementPdfService {

    private static final Logger logger = LoggerFactory.getLogger(StatementPdfService.class);

    private final LedgerService ledgerService;
    private final StatementPdfRenderer renderer;

    public StatementPdfService(LedgerService ledgerService, StatementPdfRenderer renderer) {
        this.ledgerService = ledgerService;
        this.renderer = renderer;
    }

    /** A rendered statement and the name it should be saved under. */
    public record Document(byte[] bytes, String fileName, StatementModel model) {
    }

    /**
     * @param startDate null for the whole history, in which case no balance-brought-
     *        forward row is drawn - over all time the opening balance is zero by
     *        definition and the row would be noise.
     */
    @Transactional(readOnly = true)
    public Document render(Long customerId, LocalDate startDate, LocalDate endDate) {
        CustomerStatementDTO statement =
                ledgerService.getCustomerStatement(customerId, startDate, endDate);
        StatementModel model = StatementModel.of(statement);

        byte[] bytes = renderer.render(model);
        logger.info("Rendered a {} KB statement for customer {} covering {} transactions",
                Math.max(1, bytes.length / 1024), customerId, model.totals().rowCount());

        return new Document(bytes, model.fileName(), model);
    }

    /**
     * The last seven days, for the weekly statement.
     *
     * The period is decided here rather than by the caller so every weekly statement
     * covers the same span. `to` is exclusive of nothing - it is the last day included -
     * and the caller passes the day the run is for, so a re-run of last Saturday's
     * batch produces last Saturday's document rather than this one's.
     */
    @Transactional(readOnly = true)
    public Document renderWeekly(Long customerId, LocalDate weekEnding) {
        return render(customerId, weekEnding.minusDays(6), weekEnding);
    }
}
