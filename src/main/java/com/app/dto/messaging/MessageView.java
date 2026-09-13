package com.app.dto.messaging;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.app.entity.MessageOutbox;

/**
 * One outbox row as the messaging dashboard needs it.
 *
 * A projection rather than the entity: MessageOutbox holds a Customer, and serialising
 * that drags the customer's city, route and every other association into a response
 * whose job is to fill a table with a name, a number and a status. It also keeps the
 * customer's balance and credit limit out of a payload that only needs to say who the
 * message went to.
 */
public record MessageView(
        Long id,
        Long customerId,
        String recipientName,
        String recipientMobile,
        String channel,
        String messageType,
        String status,
        LocalDate referenceDate,
        String templateId,
        String bodyPreview,
        String variables,
        int attempts,
        LocalDateTime sentAt,
        LocalDateTime deliveredAt,
        LocalDateTime readAt,
        String providerStatus,
        String providerMessageId,
        String error,
        String skipReason,
        /** True when a resend button should be offered for this row. */
        boolean resendable) {

    public static MessageView of(MessageOutbox m) {
        return new MessageView(
                m.getId(),
                m.getCustomer() == null ? null : m.getCustomer().getId(),
                m.getRecipientName(),
                m.getRecipientMobile(),
                m.getChannel() == null ? null : m.getChannel().name(),
                m.getMessageType() == null ? null : m.getMessageType().name(),
                m.getStatus() == null ? null : m.getStatus().name(),
                m.getReferenceDate(),
                m.getTemplateId(),
                m.getBodyPreview(),
                m.getVariables(),
                m.getAttempts(),
                m.getSentAt(),
                m.getDeliveredAt(),
                m.getReadAt(),
                m.getProviderStatus(),
                m.getProviderMessageId(),
                m.getError(),
                m.getSkipReason(),
                // Delivered and read are done. A skipped row has a reason that a resend
                // would not change - no number, or opted out - so the fix is on the
                // contact, not the message.
                !m.isTerminal());
    }
}
