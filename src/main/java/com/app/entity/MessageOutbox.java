package com.app.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * One message the system intends to send, and what became of it.
 *
 * Written before the provider is called and updated with the outcome, so a failure
 * leaves evidence instead of a log line that scrolls away. Until now nothing was
 * recorded at all: SendSmsService fired off an @Async call per sale row and logged
 * the response, which means there is no way to answer "did this customer get
 * yesterday's message" and no way to retry the ones that did not.
 */
@Entity
@Table(name = "message_outbox")
@Data
@EqualsAndHashCode(callSuper = false)
public class MessageOutbox extends AuditableEntity {

    public enum Channel {
        SMS,
        WHATSAPP
    }

    public enum MessageType {
        /** One per customer per trading day, replacing the per-sale-row SMS. */
        DAILY_SALE_SUMMARY,
        /** The statement PDF, weekly. */
        WEEKLY_STATEMENT,
        PAYMENT_RECEIPT,
        /** Loaded, sold, mortality, returns and the tally, to the driver. */
        TRIP_SUMMARY,
        OWNER_DIGEST
    }

    /**
     * SENT and DELIVERED are different facts and the distinction matters.
     *
     * SENT means Fast2SMS accepted the message. DELIVERED means the provider has
     * since reported it reaching the handset. A screen that showed the first as
     * though it were the second would tell somebody their customer had been informed
     * when the message may have bounced - so delivery is polled and recorded
     * separately rather than assumed from acceptance.
     */
    public enum Status {
        /** Queued, not yet attempted. */
        PENDING,
        /** Accepted by the provider. Not yet known to have arrived. */
        SENT,
        /** The provider reports it reached the handset. */
        DELIVERED,
        /** WhatsApp only, and only if the customer has read receipts on. */
        READ,
        /** Attempted and rejected, or reported undelivered; retried until the cap. */
        FAILED,
        /** Never attempted, and never will be - opted out, or no usable number. */
        SKIPPED,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Null for a driver or owner message, which has no customer behind it. */
    @ManyToOne
    @JoinColumn(name = "customer_id")
    private Customer customer;

    private String recipientName;

    /**
     * The number as it stood when the message was queued.
     *
     * Snapshotted rather than joined, because 130 customer numbers are about to be
     * corrected and an audit trail that says where a statement actually went must
     * not be rewritten by a later edit.
     */
    @Column(nullable = false, length = 20)
    private String recipientMobile;

    /*
     * Stored as varchar, not as a MySQL ENUM.
     *
     * Hibernate 6 maps @Enumerated(STRING) to a native enum column by default, and
     * ddl-auto=validate then rejects the varchar the migration created:
     *
     *   wrong column type encountered in column [channel] in table [message_outbox];
     *   found [varchar], but expecting [enum ('sms','whatsapp')]
     *
     * varchar is the right side to settle on. Adding a message type - a trip summary
     * to a driver, an owner digest - would otherwise need an ALTER TABLE on every
     * environment just to widen an enum.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 40)
    private MessageType messageType;

    /**
     * What makes a double send impossible.
     *
     * Unique in the database, built from the type, the recipient and the period -
     * "DAILY_SALE_SUMMARY:67:2026-09-09". Re-running a dispatch, or entering a
     * back-dated sale for a day already messaged, then inserts nothing rather than
     * messaging the customer twice.
     */
    @Column(nullable = false, length = 160, unique = true)
    private String idempotencyKey;

    /** The day or period the message is about, not when it was sent. */
    private LocalDate referenceDate;

    private String templateId;
    /** Template values in the order the provider expects them. */
    @Column(length = 1000)
    private String variables;
    /** The message as a person would read it, for support and for the audit. */
    @Column(length = 1000)
    private String bodyPreview;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    /** Why a message was never attempted. */
    private String skipReason;

    @Column(nullable = false)
    private int attempts = 0;
    private LocalDateTime lastAttemptAt;
    private LocalDateTime sentAt;

    /** The provider's request_id. What delivery is looked up by. */
    private String providerMessageId;
    @Column(length = 1000)
    private String providerResponse;
    @Column(length = 1000)
    private String error;

    // ---- delivery, polled from the provider -------------------------------

    private LocalDateTime deliveredAt;
    private LocalDateTime readAt;
    /** The provider's own word for it: delivered, undelivered, failed, read. */
    private String providerStatus;
    /** So a message is not polled again the moment after it was checked. */
    private LocalDateTime statusCheckedAt;

    /** True once the provider has reported an outcome that will not change. */
    public boolean isTerminal() {
        return status == Status.DELIVERED || status == Status.READ
                || status == Status.SKIPPED || status == Status.CANCELLED;
    }

    /**
     * Records what the provider says became of the message.
     *
     * An undelivered report turns a SENT row into FAILED, because that is what it is:
     * the provider accepted it and it did not arrive. Leaving it as SENT would make a
     * failure look like a success on the dashboard.
     */
    public void applyDeliveryStatus(String providerStatus, LocalDateTime when) {
        this.providerStatus = providerStatus;
        this.statusCheckedAt = LocalDateTime.now();
        if (providerStatus == null) {
            return;
        }

        switch (providerStatus.trim().toLowerCase()) {
            case "delivered" -> {
                this.status = Status.DELIVERED;
                this.deliveredAt = when == null ? LocalDateTime.now() : when;
            }
            case "read" -> {
                this.status = Status.READ;
                this.readAt = when == null ? LocalDateTime.now() : when;
                if (this.deliveredAt == null) {
                    // Read implies delivered, and the delivered report may not have
                    // been seen if polling caught up late.
                    this.deliveredAt = this.readAt;
                }
            }
            case "failed", "undelivered", "rejected" -> {
                this.status = Status.FAILED;
                this.error = truncate("Provider reported " + providerStatus, 1000);
            }
            default -> {
                // sent, accepted, queued and anything unrecognised: leave the status
                // alone and keep the provider's word for the next poll to interpret.
            }
        }
    }

    /** Marks the row sent, with whatever the provider said. */
    public void markSent(String messageId, String response) {
        this.status = Status.SENT;
        this.sentAt = LocalDateTime.now();
        this.lastAttemptAt = this.sentAt;
        this.attempts = this.attempts + 1;
        this.providerMessageId = messageId;
        this.providerResponse = truncate(response, 1000);
        this.error = null;
    }

    /** Records a failed attempt. Retry eligibility is the dispatcher's decision. */
    public void markFailed(String reason) {
        this.status = Status.FAILED;
        this.lastAttemptAt = LocalDateTime.now();
        this.attempts = this.attempts + 1;
        this.error = truncate(reason, 1000);
    }

    public void markSkipped(String reason) {
        this.status = Status.SKIPPED;
        this.skipReason = truncate(reason, 255);
    }

    /** Provider responses are unbounded; the column is not. */
    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
