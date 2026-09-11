package com.app.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

        String key = idempotencyKey(type, channel, customer.getId(), referenceDate);

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
        // Snapshotted: 130 of these numbers are about to change, and the audit trail
        // has to keep saying where the message actually went.
        message.setRecipientMobile(MobileNumberRules.normalise(customer.getMobileNo()));
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
        MobileNumberRules.Status numberStatus = MobileNumberRules.classify(customer.getMobileNo());
        if (numberStatus != MobileNumberRules.Status.VALID) {
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
        return type.name() + ":" + channel.name() + ":" + partyId
                + ":" + (referenceDate == null ? "all" : referenceDate);
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
