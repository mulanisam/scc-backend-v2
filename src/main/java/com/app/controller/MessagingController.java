package com.app.controller;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.config.MessagingProperties;
import com.app.dto.contact.ContactQualityResponse;
import com.app.dto.messaging.MessageView;
import com.app.dto.messaging.MessagingStats;
import com.app.dto.messaging.StatementRun;
import com.app.dto.messaging.WhatsAppTemplate;
import com.app.entity.MessageOutbox;
import com.app.entity.MessageOutbox.Channel;
import com.app.entity.MessageOutbox.Status;
import com.app.repository.CustomerRepository;
import com.app.repository.MessageOutboxRepository;
import com.app.service.ContactQualityService;
import com.app.service.MessagingService;
import com.app.service.report.WeeklyStatementJob;

/**
 * Operating the message outbox.
 *
 * Under /adminuser, so office staff and administrators both reach it. It was /admin
 * only, which was the wrong line to draw: the people who enter the day's sales are the
 * ones who get asked "did my message come through", and they could neither answer it
 * nor resend a failure without an administrator.
 *
 * What that admits is worth being clear about. A send spends provider credits, and the
 * message list carries customer numbers and balances - so this is office-staff access,
 * not public: the same trust already extended by /user/ledger, which shows every
 * customer's full account. DRIVER is deliberately not included; a driver has no reason
 * to see the book.
 */
@RestController
@RequestMapping("/adminuser/messaging")
public class MessagingController {

    private static final Logger logger = LoggerFactory.getLogger(MessagingController.class);

    /** A screen asking for "everything" still gets a bounded query. */
    private static final int MAX_ROWS = 500;

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private MessageOutboxRepository outboxRepository;

    @Autowired
    private ContactQualityService contactQualityService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private MessagingProperties properties;

    @Autowired
    private WeeklyStatementJob weeklyStatementJob;

    // ---- the dashboard ----------------------------------------------------

    /**
     * The figures above the table: counts by status for each channel, the delivery
     * rate, how many customers can be reached at all, and whether sending is even
     * switched on.
     *
     * @param days how far back to count, ending today
     */
    @GetMapping("/stats")
    public ResponseEntity<MessagingStats> stats(@RequestParam(defaultValue = "7") int days) {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(Math.max(0, Math.min(days, 365)));

        ContactQualityResponse contacts = contactQualityService.getContactQuality();
        MessagingStats.Reachability reachability = new MessagingStats.Reachability(
                contacts.getActiveCustomers(),
                contacts.getReachable(),
                contacts.getUnusable(),
                contacts.getOnSharedNumbers(),
                customerRepository.countWhatsappOptedIn(),
                contacts.getUnreachableBalance());

        MessagingProperties.Fast2Sms provider = properties.getFast2sms();

        // A LinkedHashMap, not Map.of: the daily template id is legitimately null until
        // one is approved, and Map.of rejects a null value with an NPE.
        Map<String, String> inUse = new LinkedHashMap<>();
        inUse.put("Daily sale (SMS)", provider.getSmsTemplateId());
        inUse.put("Daily sale (WhatsApp)", provider.getWhatsappDailyTemplateId());
        inUse.put("Balance reminder", provider.getWhatsappSaleTemplateId());
        inUse.put("Payment received", provider.getWhatsappPaymentTemplateId());
        inUse.put("Weekly statement", provider.getWhatsappStatementTemplateId());

        MessagingStats.Config config = new MessagingStats.Config(
                properties.isEnabled(),
                provider.isConfigured(),
                properties.getChannel() == null ? null : properties.getChannel().name(),
                provider.getSenderId(),
                inUse);

        return ResponseEntity.ok(MessagingStats.build(from, to,
                outboxRepository.countByChannelAndStatus(from, to), reachability, config));
    }

    /**
     * The message list the dashboard's tabs and status filter drive.
     *
     * Channel, status and type are optional and combine: the WhatsApp tab passes the
     * channel, the "failed" chip adds the status, and the statements tab adds the type.
     * Absent means no filter rather than a separate endpoint per combination.
     */
    @GetMapping("/messages")
    public ResponseEntity<List<MessageView>> messages(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "100") int limit) {

        List<MessageOutbox> rows = outboxRepository.search(
                parseChannel(channel), parseStatus(status), parseType(type), from, to,
                Limit.of(Math.max(1, Math.min(limit, MAX_ROWS))));

        return ResponseEntity.ok(rows.stream().map(MessageView::of).toList());
    }

    /** Every message that failed, newest first. The dashboard's work list. */
    @GetMapping("/failures")
    public ResponseEntity<List<MessageView>> failures(
            @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(outboxRepository
                .findFailures(Limit.of(Math.max(1, Math.min(limit, MAX_ROWS))))
                .stream().map(MessageView::of).toList());
    }

    /** Everything ever sent to one customer, for answering "did they get it". */
    @GetMapping("/customer/{customerId}")
    public ResponseEntity<List<MessageView>> forCustomer(@PathVariable Long customerId,
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(outboxRepository
                .findByCustomerIdOrderByIdDesc(customerId, Limit.of(Math.max(1, Math.min(limit, MAX_ROWS))))
                .stream().map(MessageView::of).toList());
    }

    // ---- acting on messages -----------------------------------------------

    /**
     * Sends a failed message again. Returns the new row, not the old one - a resend is
     * a new attempt with its own outcome, and the failure it replaces stays on record.
     *
     * A statement takes the second path. It is a media message: the PDF has to be rendered
     * and uploaded before the send, which the ordinary dispatcher does not do - it would
     * post the covering message with no statement attached. The retry row is queued the
     * same way either way, so the record of the attempt is identical; only the sending
     * differs.
     */
    @PostMapping("/{id}/resend")
    public ResponseEntity<MessageView> resend(@PathVariable Long id) {
        logger.warn("Resend requested for message {}", id);
        MessageOutbox retry = messagingService.resend(id);

        if (retry.getMessageType() == MessageOutbox.MessageType.WEEKLY_STATEMENT
                && retry.getStatus() == Status.PENDING) {
            retry = weeklyStatementJob.resendNow(retry);
        }
        return ResponseEntity.ok(MessageView.of(retry));
    }

    /**
     * Sends an approved template to any number, with the values typed in.
     *
     * For the cases the automatic path does not cover: a sale entered after the day's
     * messages went out, a customer asking for their balance, a number just corrected.
     * Not free text - WhatsApp only carries approved templates - so the request names a
     * template and supplies its variables in order.
     */
    @PostMapping("/send")
    public ResponseEntity<MessageView> send(@RequestBody CustomSendRequest request) {
        logger.warn("Manual {} send requested to {}", request.channel(), request.mobileNo());
        MessageOutbox sent = messagingService.sendCustomMessage(
                request.mobileNo(),
                request.recipientName(),
                parseChannel(request.channel()),
                request.templateId(),
                request.values(),
                request.customerId());
        return ResponseEntity.ok(MessageView.of(sent));
    }

    /**
     * @param values the template's variables, in the order the template declares them
     */
    public record CustomSendRequest(
            String mobileNo,
            String recipientName,
            String channel,
            String templateId,
            List<String> values,
            Long customerId) {
    }

    /**
     * Asks the provider what became of the messages it accepted, and records the
     * answers. Runs on a schedule too; this is the refresh button behind the dashboard.
     */
    @PostMapping("/sync-status")
    public ResponseEntity<Map<String, Object>> syncStatus() {
        int updated = messagingService.syncDeliveryStatus();
        return ResponseEntity.ok(Map.of("updated", updated));
    }

    /**
     * Every statement run, newest week first, with what became of each.
     *
     * The statements tab's status bar. One line per week rather than per message, because
     * the question asked of this screen is "did last week's statements go out" - 254 rows
     * do not answer that, and seven counts do.
     *
     * Not filtered by a period. There is one of these a week, so the whole history is a
     * few dozen rows, and being able to see that the run stopped happening three weeks ago
     * is the point - a date filter defaulting to "last 7 days" would hide exactly that.
     */
    @GetMapping("/weekly-statements")
    public ResponseEntity<List<StatementRun>> weeklyStatementRuns() {
        return ResponseEntity.ok(StatementRun.rollUp(
                outboxRepository.countByPeriodAndStatus(MessageOutbox.MessageType.WEEKLY_STATEMENT)));
    }

    /**
     * Builds the weekly statements now rather than waiting for Monday.
     *
     * The way to see a week's statements before any of them goes out: with
     * messaging.enabled off every row is queued and left PENDING, so the outbox can be read
     * and the PDFs downloaded from the ledger screen first.
     *
     * @param weekEnding the Sunday the week closed on; the previous completed week by default
     */
    @PostMapping("/weekly-statements")
    public ResponseEntity<WeeklyStatementJob.Result> buildWeeklyStatements(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate weekEnding) {

        LocalDate closing = weekEnding == null
                ? WeeklyStatementJob.lastCompletedWeekEnding(LocalDate.now())
                : weekEnding;

        logger.warn("Weekly statements requested for the week ending {}", closing);
        return ResponseEntity.ok(weeklyStatementJob.run(closing));
    }

    /**
     * Sends the weekly statements that are queued.
     *
     * Separate from building them, because sending is per-customer work that can fail
     * slowly: each statement is rendered, uploaded to the provider and then sent.
     */
    @PostMapping("/weekly-statements/send")
    public ResponseEntity<Map<String, Object>> sendWeeklyStatements(
            @RequestParam(defaultValue = "100") int limit) {

        int accepted = weeklyStatementJob.dispatchQueued(Math.max(1, Math.min(limit, MAX_ROWS)));
        return ResponseEntity.ok(Map.of("accepted", accepted));
    }

    /**
     * Runs the dispatcher now rather than waiting for the schedule. Respects
     * messaging.enabled, so on a dry run it reports how many are queued and sends
     * nothing.
     */
    @PostMapping("/dispatch")
    public ResponseEntity<Map<String, Object>> dispatch() {
        int sent = messagingService.dispatchPending();
        List<MessageOutbox> pending = outboxRepository.findDispatchable(3, Limit.of(MAX_ROWS));
        return ResponseEntity.ok(Map.of("accepted", sent, "stillQueued", pending.size()));
    }

    // ---- templates and test sends -----------------------------------------

    /**
     * The WhatsApp templates that actually exist on the Fast2SMS account, with the
     * variable count, category and approval status of each.
     *
     * The dashboard's custom-send panel is built from this: the variable count is what
     * decides what can be sent, so the form draws its fields from the template rather
     * than guessing.
     *
     * @param refresh true after approving a template, to drop the cached list
     */
    @GetMapping("/templates")
    public ResponseEntity<List<WhatsAppTemplate>> templates(
            @RequestParam(defaultValue = "false") boolean refresh) {
        return ResponseEntity.ok(refresh
                ? messagingService.refreshTemplates()
                : messagingService.whatsappTemplates());
    }

    /**
     * Sends one message to a given number, now, and returns what the provider said.
     *
     * The way to prove the key, sender id, template and WhatsApp number are right
     * without entering a sale. It bypasses messaging.enabled deliberately - asking for
     * a test is asking for a real send - and it is recorded in the outbox like anything
     * else, so the provider's reply is kept rather than only logged.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> sendTest(@RequestBody Map<String, String> request) {
        String mobileNo = request.get("mobileNo");
        String name = request.get("name");
        String templateId = request.get("templateId");

        // "SMS" or "WHATSAPP". Omitted means the configured default, so a quick test
        // needs only a number.
        Channel channel = parseChannel(request.get("channel"));

        logger.warn("Test {} message requested for {}",
                channel == null ? "default" : channel, mobileNo);
        MessageOutbox result = messagingService.sendTestMessage(mobileNo, name, templateId, channel);

        return ResponseEntity.ok(Map.of(
                "id", result.getId(),
                "channel", result.getChannel().name(),
                "to", result.getRecipientMobile(),
                "status", result.getStatus().name(),
                "attempts", result.getAttempts(),
                "templateId", String.valueOf(result.getTemplateId()),
                "variables", String.valueOf(result.getVariables()),
                "body", String.valueOf(result.getBodyPreview()),
                "providerResponse", String.valueOf(result.getProviderResponse()),
                "error", String.valueOf(result.getError())));
    }

    // ---- parsing ----------------------------------------------------------

    /*
     * Parsed by hand rather than bound as an enum parameter, so a stale bookmark or a
     * typed URL gets a 400 naming the valid values instead of Spring's
     * "Failed to convert value of type String" wall of text.
     */
    private Channel parseChannel(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return Channel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + value + "\" is not a channel. Use SMS or WHATSAPP.");
        }
    }

    private Status parseStatus(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return Status.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + value + "\" is not a status. Use one of "
                    + java.util.Arrays.toString(Status.values()) + ".");
        }
    }

    private MessageOutbox.MessageType parseType(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value.trim())) {
            return null;
        }
        try {
            return MessageOutbox.MessageType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("\"" + value + "\" is not a message type. Use one of "
                    + java.util.Arrays.toString(MessageOutbox.MessageType.values()) + ".");
        }
    }
}
