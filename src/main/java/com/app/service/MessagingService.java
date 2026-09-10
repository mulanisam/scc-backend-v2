package com.app.service;

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
import com.app.utility.MobileNumberRules;

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
                                 MessageType type,
                                 LocalDate referenceDate,
                                 String templateId,
                                 String variables,
                                 String bodyPreview) {

        String key = idempotencyKey(type, customer.getId(), referenceDate);

        // The unique constraint is the real guarantee; this check keeps the ordinary
        // repeat - a dispatch re-run, a back-dated sale for a day already messaged -
        // from relying on a caught constraint violation.
        Optional<MessageOutbox> existing = outboxRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            logger.debug("Message {} already queued for customer {}", key, customer.getId());
            return existing.get();
        }

        Channel channel = properties.getChannel();

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
        message.setTemplateId(templateId);
        message.setVariables(variables);
        message.setBodyPreview(bodyPreview);

        String refusal = refuse(customer, channel);
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
    private String refuse(Customer customer, Channel channel) {
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
        }
        return null;
    }

    /** "DAILY_SALE_SUMMARY:67:2026-09-09" - one message per type, per party, per period. */
    public static String idempotencyKey(MessageType type, Long partyId, LocalDate referenceDate) {
        return type.name() + ":" + partyId + ":" + (referenceDate == null ? "all" : referenceDate);
    }

    /**
     * The channel messages are currently queued on.
     *
     * Callers need this because the message itself differs by channel, and not
     * cosmetically: the approved DLT template for SMS takes three variables - name,
     * date, balance - while the detailed daily summary takes seven. Sending seven
     * values to a three-variable template is rejected by the provider, so the caller
     * has to build the message the channel's template expects.
     */
    public Channel currentChannel() {
        return properties.getChannel();
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
