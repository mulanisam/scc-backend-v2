package com.app.service.report;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.config.MessagingProperties;
import com.app.dto.messaging.WhatsAppTemplate;
import com.app.entity.Customer;
import com.app.entity.MessageOutbox;
import com.app.entity.MessageOutbox.Channel;
import com.app.entity.MessageOutbox.MessageType;
import com.app.repository.CustomerRepository;
import com.app.repository.MessageOutboxRepository;
import com.app.service.Fast2SmsClient;
import com.app.service.Fast2SmsClient.SendResult;
import com.app.service.MessagingService;
import com.app.utility.MoneyRules;
import com.app.utility.StatementFormat;

/**
 * Sends each customer their week's statement of account over WhatsApp.
 *
 * The last piece of the statement work, and the reason the PDF had to move off the
 * browser: a scheduled job has no browser to render in, so for as long as the layout lived
 * in jsPDF this could not exist. It now calls the same generator the Download button does,
 * so the document a customer receives on Sunday morning is the one the office can produce
 * on screen and compare against.
 *
 * Three things it is careful about.
 *
 * The period is fixed by the run, not by "now": Monday to Sunday of the week that just
 * ended. Re-running last week's batch produces last week's statements, which is what makes
 * a re-run safe.
 *
 * A customer with nothing in the week is skipped rather than sent an empty statement. A
 * page saying "no transactions" over a balance of two lakh invites a phone call and
 * answers nothing.
 *
 * Nothing is sent to anyone who has not opted in, and that is not a formality here: a
 * statement lists every transaction and the balance, so it goes only to a number known to
 * belong to one customer. Today no customer has opted in, so this queues and skips for
 * everybody - visibly, with the reason on each row, rather than silently doing nothing.
 */
@Service
public class WeeklyStatementJob {

    private static final Logger logger = LoggerFactory.getLogger(WeeklyStatementJob.class);

    /** The template's name on the account. Meta identifies a media template by name. */
    private static final String TEMPLATE_NAME = "weekly_statement";

    private final CustomerRepository customerRepository;
    private final MessageOutboxRepository outboxRepository;
    private final StatementPdfService statementPdfService;
    private final MessagingService messagingService;
    private final Fast2SmsClient fast2SmsClient;
    private final MessagingProperties properties;

    public WeeklyStatementJob(CustomerRepository customerRepository,
                              MessageOutboxRepository outboxRepository,
                              StatementPdfService statementPdfService,
                              MessagingService messagingService,
                              Fast2SmsClient fast2SmsClient,
                              MessagingProperties properties) {
        this.customerRepository = customerRepository;
        this.outboxRepository = outboxRepository;
        this.statementPdfService = statementPdfService;
        this.messagingService = messagingService;
        this.fast2SmsClient = fast2SmsClient;
        this.properties = properties;
    }

    /**
     * Runs early on Monday for the week that has just finished.
     *
     * A cron rather than a fixed delay, because "every seven days from whenever the
     * application last restarted" is not a weekly statement - a Wednesday restart would
     * move it to Wednesdays and nobody would notice for a month.
     */
    @Scheduled(cron = "${messaging.weekly-statement-cron:0 30 6 * * MON}",
               zone = "${messaging.timezone:Asia/Kolkata}")
    public void runScheduled() {
        if (!properties.isWeeklyStatementEnabled()) {
            logger.info("Weekly statement run skipped: messaging.weekly-statement-enabled is false");
            return;
        }
        try {
            Result result = run(lastCompletedWeekEnding(LocalDate.now()));
            // Built and sent in the one run. Kept as two methods because sending is the
            // slow, per-customer half and the office needs to be able to trigger it alone
            // after fixing a number - but on a Monday morning nobody is at the screen to
            // press the second button, and statements that sat queued until someone noticed
            // would arrive days late.
            if (result.queued() > 0) {
                dispatchQueued(result.queued());
            }
        } catch (RuntimeException e) {
            // A scheduled method that throws stops being scheduled in some setups, and this
            // one has to survive a single customer's statement failing to render.
            logger.error("Weekly statement run failed", e);
        }
    }

    /** The Sunday of the week that has just ended, from any day in the following week. */
    public static LocalDate lastCompletedWeekEnding(LocalDate today) {
        LocalDate sunday = today;
        while (sunday.getDayOfWeek() != DayOfWeek.SUNDAY) {
            sunday = sunday.minusDays(1);
        }
        // On a Sunday, the week that has ended is the one before - today is not over.
        return sunday.equals(today) ? sunday.minusWeeks(1) : sunday;
    }

    /**
     * Queues a statement for every customer who traded in the week.
     *
     * @param weekEnding the Sunday the week closed on
     * @return how many were queued, skipped and had nothing to report
     */
    @Transactional
    public Result run(LocalDate weekEnding) {
        LocalDate from = weekEnding.minusDays(6);
        logger.info("Weekly statements for {} to {}", from, weekEnding);

        int queued = 0;
        int skipped = 0;
        int nothingToReport = 0;

        for (Customer customer : customerRepository.findAll()) {
            if (customer.isObsolete()) {
                continue;
            }

            StatementPdfService.Document document;
            try {
                document = statementPdfService.render(customer.getId(), from, weekEnding);
            } catch (RuntimeException e) {
                // One customer's statement failing must not stop the other 487.
                logger.error("Could not render the statement for customer {} ({})",
                        customer.getId(), customer.getName(), e);
                continue;
            }

            if (document.model().totals().rowCount() == 0) {
                nothingToReport++;
                continue;
            }

            MessageOutbox message = queue(customer, document, from, weekEnding);
            if (message.getStatus() == MessageOutbox.Status.PENDING) {
                queued++;
            } else {
                skipped++;
            }
        }

        Result result = new Result(from, weekEnding, queued, skipped, nothingToReport);
        logger.info("Weekly statements: {} queued, {} skipped, {} with nothing to report",
                queued, skipped, nothingToReport);
        return result;
    }

    public record Result(LocalDate from, LocalDate to, int queued, int skipped, int nothingToReport) {
    }

    /**
     * Queues one statement, with the PDF's own figures as the template's variables.
     *
     * The variables restate what the attachment says, because a WhatsApp attachment is not
     * always opened: the message itself should tell the customer the period and the balance
     * even if they never tap the document.
     */
    private MessageOutbox queue(Customer customer, StatementPdfService.Document document,
                                LocalDate from, LocalDate to) {

        BigDecimal closing = MoneyRules.money(document.model().totals().closingBalance());

        String variables = String.join("|",
                safe(customer.getName()),
                StatementFormat.date(from),
                StatementFormat.date(to),
                closing.abs().setScale(0, java.math.RoundingMode.HALF_UP).toPlainString());

        String body = String.format(
                "नमस्कार %s,%n%n दिनांक %s ते %s या कालावधीचे खाते विवरण सोबत जोडले आहे.%n%n"
                        + "एकूण शिल्लक: ₹%s%n%n-सोहेल चिकन,माढा",
                safe(customer.getName()), StatementFormat.date(from), StatementFormat.date(to),
                StatementFormat.money(closing));

        return messagingService.enqueue(customer, Channel.WHATSAPP, MessageType.WEEKLY_STATEMENT,
                to, templateId(), variables, body);
    }

    /** The provider's numeric id for the weekly template, or null while none exists. */
    private String templateId() {
        return findTemplate().map(template -> String.valueOf(template.getMessageId())).orElse(null);
    }

    private Optional<WhatsAppTemplate> findTemplate() {
        return messagingService.whatsappTemplates().stream()
                .filter(template -> TEMPLATE_NAME.equalsIgnoreCase(template.getTemplateName()))
                .findFirst();
    }

    /**
     * Sends the statements that are queued and ready.
     *
     * Separate from queueing because sending is the part that can fail slowly: each message
     * needs its PDF rendered again, uploaded to the provider, and then sent - three network
     * or CPU steps per customer. Keeping them apart means a run that queues 300 statements
     * commits that fact immediately, and a provider outage delays delivery rather than
     * losing the record of what was owed.
     *
     * The PDF is rendered again rather than stored. It is derived from the ledger and takes
     * a few milliseconds; keeping 300 PDFs on disk to save that would add a directory to
     * back up, prune and secure, holding documents that each state a customer's balance.
     *
     * @return how many the provider accepted
     */
    @Transactional
    public int dispatchQueued(int limit) {
        if (!properties.isEnabled()) {
            List<MessageOutbox> waiting = outboxRepository.findByMessageTypeAndStatus(
                    MessageType.WEEKLY_STATEMENT, MessageOutbox.Status.PENDING,
                    org.springframework.data.domain.Limit.of(limit));
            logger.info("messaging.enabled=false: {} weekly statement(s) queued and not sent", waiting.size());
            return 0;
        }

        Optional<WhatsAppTemplate> template = findTemplate();
        if (template.isEmpty() || !template.get().isApproved()) {
            logger.warn("Not sending weekly statements: template {} is {}", TEMPLATE_NAME,
                    template.map(WhatsAppTemplate::getStatus).orElse("not on the account"));
            return 0;
        }

        int accepted = 0;
        for (MessageOutbox message : outboxRepository.findByMessageTypeAndStatus(
                MessageType.WEEKLY_STATEMENT, MessageOutbox.Status.PENDING,
                org.springframework.data.domain.Limit.of(limit))) {
            if (send(message, template.get().getTemplateName())) {
                accepted++;
            }
        }

        logger.info("Weekly statements: {} accepted by the provider", accepted);
        return accepted;
    }

    /**
     * Renders, uploads and sends one statement, recording the outcome on the row.
     *
     * Public because a resend needs exactly this and must not go through the ordinary
     * dispatcher. A statement is a media template: the PDF is uploaded, and the send names
     * the returned media id. The plain-text path would post the covering message with no
     * document attached - a message telling a customer their statement is attached, with
     * nothing attached, which is worse than not sending at all.
     *
     * @return true if the provider accepted it
     */
    @Transactional
    public boolean send(MessageOutbox message, String templateName) {
        if (message.getCustomer() == null || message.getReferenceDate() == null) {
            message.markSkipped("The statement has no customer or no period to cover.");
            outboxRepository.save(message);
            return false;
        }

        LocalDate to = message.getReferenceDate();
        StatementPdfService.Document document;
        try {
            document = statementPdfService.render(message.getCustomer().getId(), to.minusDays(6), to);
        } catch (RuntimeException e) {
            logger.error("Could not render the statement for message {}", message.getId(), e);
            message.markFailed("The statement could not be produced: " + e.getMessage());
            outboxRepository.save(message);
            return false;
        }

        Optional<String> mediaId = fast2SmsClient.uploadDocument(document.bytes(), document.fileName());
        if (mediaId.isEmpty()) {
            // Retryable: the dispatcher picks it up again while attempts remain.
            message.markFailed("The statement PDF could not be uploaded to the provider.");
            outboxRepository.save(message);
            return false;
        }

        SendResult result = fast2SmsClient.sendDocumentTemplate(
                message, templateName, mediaId.get(), document.fileName());

        if (result.accepted()) {
            message.markSent(result.messageId(), result.response());
        } else {
            message.markFailed(result.error()
                    + (result.response() == null ? "" : " | " + result.response()));
        }
        outboxRepository.save(message);
        return result.accepted();
    }

    /**
     * Sends one statement again, now.
     *
     * The resend button behind the statements screen. It does not throw when sending is
     * switched off or the template is unapproved: the retry row has already been queued by
     * that point, so an exception would say "this failed" about something that in fact
     * happened. The row is left PENDING and the screen's banner is what explains why
     * nothing left the building - a standing condition belongs in a banner, not in a toast
     * that disappears.
     *
     * @return the message, with whatever outcome the attempt recorded
     */
    @Transactional
    public MessageOutbox resendNow(MessageOutbox message) {
        if (!properties.isEnabled()) {
            logger.info("Statement {} queued; not sent because messaging.enabled is false",
                    message.getId());
            return message;
        }
        Optional<WhatsAppTemplate> template = findTemplate();
        if (template.isEmpty() || !template.get().isApproved()) {
            logger.warn("Statement {} queued; template {} is {}", message.getId(), TEMPLATE_NAME,
                    template.map(WhatsAppTemplate::getStatus).orElse("not on the account"));
            return message;
        }
        send(message, template.get().getTemplateName());
        return message;
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("|", " ").trim();
    }
}
