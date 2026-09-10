-- Message outbox, and per-customer consent.
--
-- Two things have to exist before a ledger statement is sent to a customer over
-- WhatsApp: a record of what was sent, and a way for a customer to say no.
--
-- What messaging looks like today: SendSmsService is called per sale row from
-- inside the sale transaction, @Async and fire-and-forget. It logs a line and, on
-- permanent failure, logs another. Nothing is stored. So there is no way to answer
-- "did Javed get yesterday's message", no way to retry the ones that failed, and a
-- customer with two lines on one trip gets two messages - which happens on 175
-- trips in this data. Once the message carries a full sale summary or a statement
-- PDF, none of that is acceptable.

-- ---------------------------------------------------------------------------
-- 1. Consent.
-- ---------------------------------------------------------------------------
-- WhatsApp requires an opt-in for business-initiated messages and requires a
-- block to be honoured. Both are recorded per customer rather than inferred:
-- opt-in has a timestamp because it has to be evidenced, and opt-out is a flag
-- that the send path checks before every message.
--
-- Existing customers are left opted-out of WhatsApp deliberately. Nobody has
-- agreed to receive statements yet, and defaulting 488 customers into it would be
-- the wrong way round - the SMS they get today is a different, far smaller
-- disclosure than a statement carrying their balance.

ALTER TABLE `customer`
    ADD COLUMN `whatsapp_opt_in_at` DATETIME(6) NULL COMMENT 'When this customer agreed to WhatsApp messages',
    ADD COLUMN `whatsapp_opt_out`   TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'Set when the customer asks to stop',
    ADD COLUMN `whatsapp_opt_out_at` DATETIME(6) NULL,
    ADD COLUMN `messaging_notes`    VARCHAR(255) NULL COMMENT 'Why messaging is off for this customer, if it is';

-- ---------------------------------------------------------------------------
-- 2. The outbox.
-- ---------------------------------------------------------------------------
-- One row per message the system intends to send, written before the provider is
-- called and updated with the outcome. That ordering is the point: a message that
-- was attempted and failed leaves a row, so it can be found and retried, and a
-- message that was never attempted is visibly PENDING rather than absent.
--
-- recipient_mobile is a snapshot rather than a join. The number on the customer
-- record changes - 130 of them are about to - and an audit trail that says where a
-- statement actually went cannot be rewritten by a later correction.

CREATE TABLE `message_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,

    -- Nullable: a trip summary goes to a driver and a digest to the owner, neither
    -- of which is a customer.
    `customer_id` BIGINT NULL,
    `recipient_name` VARCHAR(255) NULL,
    `recipient_mobile` VARCHAR(20) NOT NULL,

    `channel` VARCHAR(20) NOT NULL COMMENT 'SMS or WHATSAPP',
    `message_type` VARCHAR(40) NOT NULL COMMENT 'DAILY_SALE_SUMMARY, WEEKLY_STATEMENT, PAYMENT_RECEIPT, ...',

    /*
     * What makes a double send impossible. Built from the message type, the
     * recipient and the period it covers - "DAILY_SALE_SUMMARY:67:2026-09-09" - so
     * re-running a day's dispatch, or entering a back-dated sale for a day already
     * messaged, inserts nothing rather than messaging the customer twice.
     */
    `idempotency_key` VARCHAR(160) NOT NULL,
    `reference_date` DATE NULL COMMENT 'The day or period the message is about',

    `template_id` VARCHAR(40) NULL COMMENT 'Provider template, for a DLT or WhatsApp send',
    `variables` VARCHAR(1000) NULL COMMENT 'Template values, pipe separated as the provider expects',
    `body_preview` VARCHAR(1000) NULL COMMENT 'The message as a human would read it',

    `status` VARCHAR(20) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING, SENT, FAILED, SKIPPED, CANCELLED',
    `skip_reason` VARCHAR(255) NULL COMMENT 'Why a message was never attempted',

    `attempts` INT NOT NULL DEFAULT 0,
    `last_attempt_at` DATETIME(6) NULL,
    `sent_at` DATETIME(6) NULL,

    `provider_message_id` VARCHAR(120) NULL,
    `provider_response` VARCHAR(1000) NULL,
    `error` VARCHAR(1000) NULL,

    `created_by` VARCHAR(100) NULL,
    `created_at` DATETIME(6) NULL,
    `updated_by` VARCHAR(100) NULL,
    `updated_at` DATETIME(6) NULL,

    PRIMARY KEY (`id`),
    -- The constraint, not just an index: two rows for the same message cannot exist.
    UNIQUE KEY `uk_message_outbox_idempotency` (`idempotency_key`),
    KEY `idx_message_outbox_status` (`status`, `id`),
    KEY `idx_message_outbox_customer` (`customer_id`, `reference_date`),
    KEY `idx_message_outbox_type_date` (`message_type`, `reference_date`),
    CONSTRAINT `fk_message_outbox_customer`
        FOREIGN KEY (`customer_id`) REFERENCES `customer` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Note on why nothing is backfilled here: the messages sent so far were not
-- recorded anywhere, so there is nothing to import. The outbox starts empty and
-- becomes the record from the first dispatch onward.
