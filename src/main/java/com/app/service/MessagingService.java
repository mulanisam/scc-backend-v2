package com.app.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.app.config.MessagingProperties;
import com.app.entity.Customer;
import com.app.entity.MessageOutbox;
import com.app.entity.MessageOutbox.Channel;
import com.app.entity.MessageOutbox.MessageType;
import com.app.entity.MessageOutbox.Status;
import com.app.exception.ResourceNotFoundException;
import com.app.repository.CustomerRepository;
import com.app.repository.MessageOutboxRepository;
import com.app.service.Fast2SmsClient.SendResult;
import com.app.dto.messaging.WhatsAppTemplate;
import com.app.utility.MobileNumberRules;
import com.app.utility.SmsMessageBuilder;

/**
 * Queues messages and sends them.
 *
 * Two halves, deliberately separated. Queueing writes a row and returns - it never
 * calls the provider, so a slow API cannot hold a sale transaction open, and a sale
 * is never rolled back because a message failed. Dispatching picks the queue up
 * afterwards on a schedule.
 *
 * What this replaces: SendSmsService called @Async from inside salesBulkEntry, once
 * per sale row. Nothing was recorded, so there was no way to know whether a message
 * went; a customer with two lines on one trip got two messages, which happens on 175
 * trips in this data; and a failure produced a log line and nothing else.
 *
 * Every send passes three gates before the provider is called - a usable number,
 * consent for the channel, and no existing row for the same message - because the
 * thing being sent next carries a customer's balance.
 */
@Service
public class MessagingService {

    private static final Logger logger = LoggerFactory.getLogger(MessagingService.class);

    @Autowired
    private MessageOutboxRepository outboxRepository;

    @Autowired
    private MessagingProperties properties;

    @Autowired
    private Fast2SmsClient fast2SmsClient;

    @Autowired
    private CustomerRepository customerRepository;

    /** The provider keeps three days of WhatsApp logs, so older rows never gain one. */
    private static final int DELIVERY_HISTORY_DAYS = 3;

    /**
     * Queues one message for a customer, or records why it cannot be sent.
     *
     * Joins the caller's transaction, so the outbox row and the sale commit together.
     * That is the point of an outbox: if the sale rolls back the message never
     * existed, and if the sale is committed the message is guaranteed to be queued -
     * no window in which one happened and the other did not.
     *
     * This was REQUIRES_NEW to begin with, on the reasoning that a sale should not
     * fail because a message could not be queued. That deadlocked immediately, and
     * for a reason worth recording: the second transaction inserts a row whose
     * foreign key points at the customer, and the sale transaction is already
     * holding that customer row locked from writing the new balance. The inner
     * transaction waits for a lock the outer one holds, the outer waits for the
     * inner to return, and MySQL breaks the tie with "Lock wait timeout exceeded"
     * about fifty seconds later. Same transaction, no second lock, no deadlock.
     *
     * @return the row, whether it ended up queued or skipped
     */
    @Transactional
    public MessageOutbox enqueue(Customer customer,
                                 Channel channel,
                                 MessageType type,
                                 LocalDate referenceDate,
                                 String templateId,
                                 String variables,
                                 String bodyPreview) {
        return enqueue(customer, channel, type, referenceDate, templateId, variables, bodyPreview, null);
    }

    /**
     * Joins the caller's transaction for the reason above: the outbox row and the sale
     * or payment it belongs to commit together, or neither does.
     *
     * @param occurrence distinguishes several messages of one type on one day, where
     *        that is legitimate - a customer can pay twice in a morning, and each
     *        receipt is its own message. Null for the once-a-day kinds, whose whole
     *        point is that a re-run or a back-dated entry does not message twice.
     */
    @Transactional
    public MessageOutbox enqueue(Customer customer,
                                 Channel channel,
                                 MessageType type,
                                 LocalDate referenceDate,
                                 String templateId,
                                 String variables,
                                 String bodyPreview,
                                 Long occurrence) {

        String key = idempotencyKey(type, channel, customer.getId(), referenceDate, occurrence);

        // The unique constraint is the real guarantee; this check keeps the ordinary
        // repeat - a dispatch re-run, a back-dated sale for a day already messaged -
        // from relying on a caught constraint violation.
        Optional<MessageOutbox> existing = outboxRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            logger.debug("Message {} already queued for customer {}", key, customer.getId());
            return existing.get();
        }

        MessageOutbox message = new MessageOutbox();
        message.setCustomer(customer);
        message.setRecipientName(customer.getName());
        /*
         * Snapshotted: 130 of these numbers are about to change, and the audit trail has
         * to keep saying where the message actually went.
         *
         * messagingNumber() picks the main number, or the second one when the main is
         * unusable - one message to one phone, never both. Sending a statement twice
         * would double the exposure of a balance and the provider cost for no benefit.
         * Null here means neither number works, and refuse() below is what reports it;
         * the column is not-null, so a placeholder keeps the row insertable and the
         * skip reason explains it.
         */
        String recipient = customer.messagingNumber();
        message.setRecipientMobile(recipient == null ? "" : recipient);
        message.setChannel(channel);
        message.setMessageType(type);
        message.setIdempotencyKey(key);
        message.setReferenceDate(referenceDate);
        message.setVariables(variables);
        message.setBodyPreview(bodyPreview);

        String resolvedTemplate = resolveTemplateId(channel, type, templateId);
        message.setTemplateId(resolvedTemplate);

        String refusal = refuse(customer, channel, type, resolvedTemplate);
        // Only worth asking the template when nothing else has already stopped the
        // message, and it rewrites the preview to the approved wording as a
        // side effect.
        if (refusal == null) {
            refusal = checkAgainstTemplate(message);
        }
        if (refusal != null) {
            message.markSkipped(refusal);
            logger.info("Not messaging {} ({}): {}", customer.getName(), customer.getId(), refusal);
        }

        return outboxRepository.save(message);
    }

    /**
     * Why this customer cannot be sent this kind of message, or null if they can.
     *
     * Both gates matter and they are not the same question. A usable number says we
     * can reach them; consent says they agreed to be reached this way. WhatsApp
     * requires an opt-in for business-initiated messages and requires a block to be
     * honoured, and a statement carrying a balance is not something to send to
     * somebody who never asked for it.
     */
    private String refuse(Customer customer, Channel channel, MessageType type, String templateId) {
        /*
         * Either number will do, and the same choice the send path makes.
         *
         * Judging the main number alone would skip a customer whose main number is
         * blank but whose second one is fine - exactly the case the second column was
         * added for. The reason given names the main number's problem, because that is
         * still the field somebody should eventually fix.
         */
        if (customer.messagingNumber() == null) {
            MobileNumberRules.Status numberStatus = MobileNumberRules.classify(customer.getMobileNo());
            return MobileNumberRules.describe(numberStatus);
        }
        if (channel == Channel.WHATSAPP) {
            if (customer.isWhatsappOptOut()) {
                return "Customer has opted out of WhatsApp messages";
            }
            if (customer.getWhatsappOptInAt() == null) {
                return "Customer has not opted in to WhatsApp messages";
            }
            // The daily summary carries eight variables and needs its own approved
            // template. Without one, resolveTemplateId falls back to the
            // three-variable pending_balance template, which would be rejected for
            // every customer at once - so it is held with a reason instead.
            //
            // checkAgainstTemplate would also catch this, and with a better message,
            // because it compares against the provider's own var_count. This stays as
            // the guard for when that list cannot be reached: a template mismatch is
            // not something to discover one rejection per customer.
            if (type == MessageType.DAILY_SALE_SUMMARY
                    && !properties.getFast2sms().hasWhatsappDailyTemplate()) {
                return "The 8-variable WhatsApp daily template is not approved yet."
                        + " Set FAST2SMS_WA_DAILY_TEMPLATE_ID once it is.";
            }
        }
        return null;
    }

    /**
     * "DAILY_SALE_SUMMARY:WHATSAPP:67:2026-09-09" - one message per type, per
     * channel, per party, per period.
     *
     * The channel is part of the key because a customer can legitimately be sent both
     * an SMS and a WhatsApp message for the same day - the operator chooses per entry
     * - and without it the second channel would be silently treated as a duplicate of
     * the first and never queued.
     */
    public static String idempotencyKey(MessageType type, Channel channel, Long partyId, LocalDate referenceDate) {
        return idempotencyKey(type, channel, partyId, referenceDate, null);
    }

    /**
     * @param occurrence appended when several of one type on one day are legitimate -
     *        the payment id on a receipt, so two payments in a morning send two
     *        receipts instead of the second being taken for a duplicate of the first.
     */
    public static String idempotencyKey(MessageType type, Channel channel, Long partyId,
                                       LocalDate referenceDate, Long occurrence) {
        return type.name() + ":" + channel.name() + ":" + partyId
                + ":" + (referenceDate == null ? "all" : referenceDate)
                + (occurrence == null ? "" : ":" + occurrence);
    }

    /** The channel used when a caller does not name one, such as a test send. */
    public Channel defaultChannel() {
        return properties.getChannel();
    }

    /**
     * Which provider template will carry this message, decided when it is queued.
     *
     * Resolved here rather than left to the client at send time. The client does fall
     * back to a configured default, but a row whose template_id is null cannot say
     * afterwards what the customer was sent, and the preview cannot be rendered from
     * a template nobody recorded - which is exactly how a test send came to store one
     * wording while the recipient read another.
     */
    private String resolveTemplateId(Channel channel, MessageType type, String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        MessagingProperties.Fast2Sms config = properties.getFast2sms();

        if (channel == Channel.SMS) {
            return config.getSmsTemplateId();
        }

        switch (type) {
            case DAILY_SALE_SUMMARY:
                // The eight-variable template when it exists; otherwise the
                // three-variable one, which the balance-only message fits.
                return config.hasWhatsappDailyTemplate()
                        ? config.getWhatsappDailyTemplateId()
                        : config.getWhatsappSaleTemplateId();
            case PAYMENT_RECEIPT:
                return config.getWhatsappPaymentTemplateId();
            default:
                return config.getWhatsappSaleTemplateId();
        }
    }

    // ---- templates --------------------------------------------------------

    /**
     * The WhatsApp templates on the account, cached for the process.
     *
     * Cached because it is consulted on every WhatsApp message to check the variable
     * count and to render the preview, and templates change about once a month.
     * refreshTemplates() clears it after one is approved.
     */
    private volatile List<WhatsAppTemplate> templateCache;

    public List<WhatsAppTemplate> whatsappTemplates() {
        List<WhatsAppTemplate> cached = templateCache;
        if (cached == null) {
            cached = fast2SmsClient.fetchTemplates();
            templateCache = cached;
        }
        return cached;
    }

    public List<WhatsAppTemplate> refreshTemplates() {
        templateCache = fast2SmsClient.fetchTemplates();
        return templateCache;
    }

    private Optional<WhatsAppTemplate> templateByMessageId(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return Optional.empty();
        }
        return whatsappTemplates().stream()
                .filter(template -> messageId.equals(String.valueOf(template.getMessageId())))
                .findFirst();
    }

    /**
     * Checks the values against the template that will carry them, and renders the
     * preview from the approved body.
     *
     * Both halves were wrong before this existed. The variable count was never
     * checked, so an eight-value message against a three-variable template would
     * have been rejected once per customer with nothing to explain why. And the
     * preview stored on the outbox row was the application's own wording, not the
     * template's - for 12082 those are entirely different sentences, so the record of
     * what a customer had been sent described a message that was never sent.
     *
     * @return a refusal reason, or null when the message can go
     */
    private String checkAgainstTemplate(MessageOutbox message) {
        if (message.getChannel() != Channel.WHATSAPP) {
            return null;
        }

        Optional<WhatsAppTemplate> found = templateByMessageId(message.getTemplateId());
        if (found.isEmpty()) {
            // Not fatal: the provider is the authority, and the list may be
            // unreachable. The send is attempted and its own reply recorded.
            logger.debug("No local record of WhatsApp template {}", message.getTemplateId());
            return null;
        }

        WhatsAppTemplate template = found.get();
        if (!template.isApproved()) {
            return "WhatsApp template " + template.getTemplateName()
                    + " is not approved (" + template.getStatus() + ")";
        }

        int supplied = message.getVariables() == null ? 0 : message.getVariables().split("\\|", -1).length;
        int expected = template.getVarCount() == null ? template.placeholderCount() : template.getVarCount();

        if (expected > 0 && supplied != expected) {
            return "Template " + template.getTemplateName() + " takes " + expected
                    + " variables but " + supplied + " were supplied";
        }

        // The preview becomes what the customer will actually read.
        message.setBodyPreview(template.render(message.getVariables()));
        return null;
    }

    /**
     * Sends one message to a given number, immediately, and reports what happened.
     *
     * For proving the provider is configured - key, sender id, template, WhatsApp
     * number - without waiting for a sale to be entered. It goes through the outbox
     * like everything else, so the attempt is recorded and the provider's own reply
     * is stored rather than only logged.
     *
     * Two deliberate differences from a real message. It ignores messaging.enabled,
     * because the point of asking for a test is to actually send one. And its
     * idempotency key carries a timestamp, so the same number can be tested twice -
     * every other message type is keyed to a period precisely so that it cannot be.
     *
     * The number is still validated: a test that "succeeds" against a nine-digit
     * number proves nothing.
     */
    @Transactional
    public MessageOutbox sendTestMessage(String mobileNo, String recipientName,
                                         String templateId, Channel requestedChannel) {
        MobileNumberRules.Status numberStatus = MobileNumberRules.classify(mobileNo);
        if (numberStatus != MobileNumberRules.Status.VALID) {
            throw new IllegalArgumentException("\"" + mobileNo + "\" cannot be used: "
                    + MobileNumberRules.describe(numberStatus) + ".");
        }
        if (!properties.getFast2sms().isConfigured()) {
            throw new IllegalStateException(
                    "Fast2SMS is not configured. Set FAST2SMS_API_KEY in .env and restart.");
        }

        Channel channel = requestedChannel == null ? properties.getChannel() : requestedChannel;
        String name = recipientName == null || recipientName.isBlank() ? "Test" : recipientName.trim();
        LocalDate today = LocalDate.now();

        // Three values, matching what the existing approved templates take on both
        // channels. The detailed seven-variable summary needs its own template first.
        SmsMessageBuilder.Message built = SmsMessageBuilder.dailyBalance(name, today, BigDecimal.ZERO);

        MessageOutbox message = new MessageOutbox();
        message.setRecipientName(name);
        message.setRecipientMobile(MobileNumberRules.normalise(mobileNo));
        message.setChannel(channel);
        message.setMessageType(MessageType.DAILY_SALE_SUMMARY);
        message.setIdempotencyKey("TEST:" + MobileNumberRules.normalise(mobileNo)
                + ":" + System.currentTimeMillis());
        message.setReferenceDate(today);
        message.setTemplateId(resolveTemplateId(channel, MessageType.DAILY_SALE_SUMMARY, templateId));
        message.setVariables(built.variables());
        message.setBodyPreview(built.body());

        // Renders the preview from the approved template, so the record says what the
        // recipient actually read rather than what this code would have written.
        checkAgainstTemplate(message);

        MessageOutbox saved = outboxRepository.save(message);
        logger.warn("Sending a test {} message to {} - this bypasses messaging.enabled",
                channel, saved.getRecipientMobile());

        SendResult result = fast2SmsClient.send(saved);
        if (result.accepted()) {
            saved.markSent(result.messageId(), result.response());
        } else {
            saved.markFailed(result.error() + (result.response() == null ? "" : " | " + result.response()));
        }
        return outboxRepository.save(saved);
    }

    // ---- delivery status --------------------------------------------------

    /**
     * Asks the provider what became of messages it accepted.
     *
     * Accepted is not delivered. Without this, the dashboard could only ever say "we
     * handed it over", which is the kind of half-truth that has somebody assuring a
     * customer they were told about a balance they never saw.
     *
     * WhatsApp is fetched as a date range because its log cannot be filtered by
     * request id; SMS is fetched one report at a time because that is the only shape
     * offered. Both are matched on provider_message_id.
     *
     * Runs every ten minutes. The provider keeps three days of WhatsApp history, so
     * anything older than that is left alone - it will never gain a status now.
     */
    @Scheduled(fixedDelayString = "${messaging.delivery-poll-interval-ms:600000}",
               initialDelayString = "${messaging.delivery-poll-initial-delay-ms:90000}")
    public void syncDeliveryScheduled() {
        try {
            syncDeliveryStatus();
        } catch (RuntimeException e) {
            logger.error("Delivery status sync failed", e);
        }
    }

    /**
     * @return how many rows gained a delivery outcome
     */
    public int syncDeliveryStatus() {
        LocalDate from = LocalDate.now().minusDays(DELIVERY_HISTORY_DAYS);
        List<MessageOutbox> awaiting = outboxRepository.findAwaitingDelivery(
                Status.SENT, from.atStartOfDay());

        if (awaiting.isEmpty()) {
            return 0;
        }

        int updated = 0;

        // One call covers every WhatsApp message in the window.
        boolean anyWhatsapp = awaiting.stream().anyMatch(m -> m.getChannel() == Channel.WHATSAPP);
        Map<String, Fast2SmsClient.DeliveryReport> whatsappReports = new HashMap<>();
        if (anyWhatsapp) {
            for (Fast2SmsClient.DeliveryReport report :
                    fast2SmsClient.fetchWhatsappDelivery(from, LocalDate.now())) {
                if (report.requestId() != null) {
                    whatsappReports.put(report.requestId(), report);
                }
            }
        }

        for (MessageOutbox message : awaiting) {
            Fast2SmsClient.DeliveryReport report = message.getChannel() == Channel.WHATSAPP
                    ? whatsappReports.get(message.getProviderMessageId())
                    : fast2SmsClient.fetchSmsDelivery(message.getProviderMessageId()).orElse(null);

            if (report == null) {
                // No report yet. Stamp the check so the next pass can prefer rows
                // that have waited longest.
                message.setStatusCheckedAt(LocalDateTime.now());
                outboxRepository.save(message);
                continue;
            }

            Status before = message.getStatus();
            message.applyDeliveryStatus(report.status(), parseTimestamp(report.timestamp()));
            outboxRepository.save(message);
            if (message.getStatus() != before) {
                updated++;
            }
        }

        logger.info("Delivery sync: {} of {} message(s) gained an outcome", updated, awaiting.size());
        return updated;
    }

    /**
     * The provider's timestamps arrive in more than one shape, and an unparseable one
     * is not worth failing a sync over - the row still gets its status, dated now.
     */
    private LocalDateTime parseTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            // Epoch seconds, which is what the WhatsApp Cloud API uses.
            if (trimmed.matches("\\d{10}")) {
                return LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochSecond(Long.parseLong(trimmed)), ZoneId.systemDefault());
            }
            if (trimmed.matches("\\d{13}")) {
                return LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(Long.parseLong(trimmed)), ZoneId.systemDefault());
            }
            return LocalDateTime.parse(trimmed.replace(' ', 'T'));
        } catch (RuntimeException e) {
            logger.debug("Unrecognised delivery timestamp \"{}\"", trimmed);
            return null;
        }
    }

    // ---- resend and ad-hoc sends ------------------------------------------

    /**
     * Sends a failed message again, as a new row.
     *
     * A new row rather than resetting the old one, so the record keeps both the
     * failure and the retry. Overwriting the original would erase the evidence that
     * anything went wrong - which is the whole reason the outbox exists.
     *
     * The idempotency key carries a resend counter, so the original key stays intact
     * and a second resend is still possible.
     */
    @Transactional
    public MessageOutbox resend(Long outboxId) {
        MessageOutbox original = outboxRepository.findById(outboxId)
                .orElseThrow(() -> new ResourceNotFoundException("Message " + outboxId + " was not found."));

        if (original.getStatus() == Status.DELIVERED || original.getStatus() == Status.READ) {
            throw new IllegalStateException("Message " + outboxId
                    + " was already delivered; there is nothing to resend.");
        }

        long attempt = outboxRepository.countByIdempotencyKeyStartingWith(
                original.getIdempotencyKey() + ":resend") + 1;

        MessageOutbox retry = new MessageOutbox();
        retry.setCustomer(original.getCustomer());
        retry.setRecipientName(original.getRecipientName());
        retry.setRecipientMobile(original.getRecipientMobile());
        retry.setChannel(original.getChannel());
        retry.setMessageType(original.getMessageType());
        retry.setIdempotencyKey(original.getIdempotencyKey() + ":resend" + attempt);
        retry.setReferenceDate(original.getReferenceDate());
        retry.setTemplateId(original.getTemplateId());
        retry.setVariables(original.getVariables());
        retry.setBodyPreview(original.getBodyPreview());

        // Checked again: the number may have been corrected, or consent withdrawn,
        // since the original attempt.
        if (original.getCustomer() != null) {
            String refusal = refuse(original.getCustomer(), original.getChannel(),
                    original.getMessageType(), original.getTemplateId());
            if (refusal != null) {
                retry.markSkipped(refusal);
            }
        }

        MessageOutbox saved = outboxRepository.save(retry);
        logger.info("Queued a resend of message {} as {}", outboxId, saved.getId());

        // Sent immediately rather than waiting for the schedule: a resend is somebody
        // watching the screen, and its whole point is to see the outcome now.
        if (saved.getStatus() == Status.PENDING && properties.isEnabled()) {
            sendOne(saved);
        }
        return saved;
    }

    /**
     * Sends an approved template to any number, with values supplied by hand.
     *
     * For the cases the automatic path does not cover - telling one customer about a
     * sale entered late, chasing a balance, or checking the provider after a
     * configuration change. It is not free text: WhatsApp only carries approved
     * templates outside a 24-hour reply window, so the caller picks a template and
     * fills its variables, and the variable count is checked against the provider's
     * own before anything is sent.
     *
     * Recorded in the outbox like every other message. An ad-hoc send that left no
     * trace would be the one message nobody could account for.
     */
    @Transactional
    public MessageOutbox sendCustomMessage(String mobileNo,
                                           String recipientName,
                                           Channel channel,
                                           String templateId,
                                           List<String> values,
                                           Long customerId) {

        MobileNumberRules.Status numberStatus = MobileNumberRules.classify(mobileNo);
        if (numberStatus != MobileNumberRules.Status.VALID) {
            throw new IllegalArgumentException("\"" + mobileNo + "\" cannot be used: "
                    + MobileNumberRules.describe(numberStatus) + ".");
        }
        if (!properties.getFast2sms().isConfigured()) {
            throw new IllegalStateException("Fast2SMS is not configured. Set FAST2SMS_API_KEY in .env.");
        }
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("The template's values are required.");
        }

        Channel resolvedChannel = channel == null ? properties.getChannel() : channel;
        String resolvedTemplate = templateId != null && !templateId.isBlank()
                ? templateId
                : resolveTemplateId(resolvedChannel, MessageType.DAILY_SALE_SUMMARY, null);

        // A pipe inside a value would shift every later one into the wrong slot.
        String variables = values.stream()
                .map(value -> value == null ? "" : value.replace("|", " ").trim())
                .collect(Collectors.joining("|"));

        MessageOutbox message = new MessageOutbox();
        message.setRecipientName(recipientName == null || recipientName.isBlank() ? "Manual" : recipientName.trim());
        message.setRecipientMobile(MobileNumberRules.normalise(mobileNo));
        message.setChannel(resolvedChannel);
        message.setMessageType(MessageType.DAILY_SALE_SUMMARY);
        message.setTemplateId(resolvedTemplate);
        message.setVariables(variables);
        message.setReferenceDate(LocalDate.now());
        // Timestamped, because an ad-hoc send is deliberately repeatable - the same
        // person may need telling twice.
        message.setIdempotencyKey("MANUAL:" + MobileNumberRules.normalise(mobileNo)
                + ":" + System.currentTimeMillis());

        if (customerId != null) {
            customerRepository.findById(customerId).ifPresent(message::setCustomer);
        }

        String refusal = checkAgainstTemplate(message);
        if (refusal != null) {
            // Not saved as skipped: this is somebody at a screen who needs telling
            // what is wrong so they can correct it and try again.
            throw new IllegalArgumentException(refusal);
        }

        MessageOutbox saved = outboxRepository.save(message);
        logger.warn("Manual {} send to {} using template {}",
                resolvedChannel, saved.getRecipientMobile(), resolvedTemplate);

        SendResult result = fast2SmsClient.send(saved);
        if (result.accepted()) {
            saved.markSent(result.messageId(), result.response());
        } else {
            saved.markFailed(result.error() + (result.response() == null ? "" : " | " + result.response()));
        }
        return outboxRepository.save(saved);
    }

    // ---- dispatch ---------------------------------------------------------

    /**
     * Sends what is queued. Runs every two minutes.
     *
     * Deliberately not triggered by queueing. A sale entry returns as soon as its
     * rows are written; the messages for it go out on the next pass, which keeps the
     * provider off the critical path and means a provider outage delays messages
     * rather than blocking trading.
     */
    @Scheduled(fixedDelayString = "${messaging.dispatch-interval-ms:120000}",
               initialDelayString = "${messaging.dispatch-initial-delay-ms:30000}")
    public void dispatchScheduled() {
        try {
            dispatchPending();
        } catch (RuntimeException e) {
            // A scheduled method that throws stops being scheduled in some setups;
            // this one has to survive a bad batch.
            logger.error("Message dispatch run failed", e);
        }
    }

    /**
     * @return how many messages were accepted by the provider
     */
    public int dispatchPending() {
        List<MessageOutbox> batch = outboxRepository.findDispatchable(
                properties.getMaxAttempts(), Limit.of(properties.getBatchSize()));

        if (batch.isEmpty()) {
            return 0;
        }

        if (!properties.isEnabled()) {
            // The dry-run state. Rows stay PENDING and readable, so a day's messages
            // can be inspected - who, what number, what text - before anything is
            // sent to 358 customers whose numbers are only mostly right.
            logger.info("messaging.enabled=false: {} message(s) queued and not sent", batch.size());
            return 0;
        }

        int sent = 0;
        for (MessageOutbox message : batch) {
            if (sendOne(message)) {
                sent++;
            }
        }
        logger.info("Message dispatch: {} of {} accepted by the provider", sent, batch.size());
        return sent;
    }

    /**
     * One message, in its own transaction, so a failure on one does not roll back
     * the outcomes already recorded for the others in the batch.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean sendOne(MessageOutbox message) {
        // Re-read: the row may have been sent or cancelled since the batch was taken.
        MessageOutbox current = outboxRepository.findById(message.getId()).orElse(null);
        if (current == null || current.getStatus() == Status.SENT
                || current.getStatus() == Status.SKIPPED
                || current.getStatus() == Status.CANCELLED) {
            return false;
        }

        /*
         * A statement carries a PDF, and this path cannot attach one.
         *
         * fast2SmsClient.send posts to the plain template endpoint, which has no media
         * parameter - so a statement sent from here would arrive as a message saying the
         * statement is attached, with nothing attached. Left queued for
         * WeeklyStatementJob, which uploads the document first. findDispatchable already
         * excludes these; this is the guard for the other way in, a resend by id.
         */
        if (current.getMessageType() == MessageType.WEEKLY_STATEMENT) {
            logger.debug("Message {} is a statement and is left for the statement dispatcher",
                    current.getId());
            return false;
        }

        SendResult result = fast2SmsClient.send(current);
        if (result.accepted()) {
            current.markSent(result.messageId(), result.response());
        } else {
            current.markFailed(result.error() + (result.response() == null ? "" : " | " + result.response()));
            if (current.getAttempts() >= properties.getMaxAttempts()) {
                logger.error("Message {} to {} failed {} times and will not be retried: {}",
                        current.getId(), current.getRecipientMobile(), current.getAttempts(), result.error());
            }
        }
        outboxRepository.save(current);
        return result.accepted();
    }
}
