-- Delivery status on the outbox.
--
-- Until now a row went to SENT when Fast2SMS accepted it, and stopped there. That is
-- not delivery: accepted means the provider took the message, not that a phone
-- received it. A dashboard showing "sent" as though it meant delivered would be
-- telling somebody their customer got a message that may have bounced.
--
-- Fast2SMS reports the real outcome two ways, and both are keyed on the request_id
-- already stored in provider_message_id:
--
--   WhatsApp  GET /dev/whatsapp_logs?from=&to=   (three days of history)
--   SMS       GET /dev/dlr/{request_id}
--
-- Polled rather than pushed. The webhook endpoints exist, but a webhook needs a
-- public URL and this application runs on localhost - polling works today and can be
-- swapped for a webhook later without changing these columns.

ALTER TABLE `message_outbox`
    ADD COLUMN `delivered_at` DATETIME(6) NULL
        COMMENT 'When the provider reported the message delivered to the handset',
    ADD COLUMN `read_at` DATETIME(6) NULL
        COMMENT 'WhatsApp only, and only when the customer has read receipts on',
    ADD COLUMN `provider_status` VARCHAR(40) NULL
        COMMENT 'The provider status verbatim - delivered, undelivered, failed, read',
    ADD COLUMN `status_checked_at` DATETIME(6) NULL
        COMMENT 'Last time delivery was polled, so a check is not repeated needlessly';

-- Finding the rows worth polling: sent, not yet terminal, recent enough that the
-- provider still holds the log.
CREATE INDEX `idx_message_outbox_delivery_check`
    ON `message_outbox` (`status`, `sent_at`);

-- status gains DELIVERED and READ. No change is needed here because the column is a
-- VARCHAR rather than a MySQL ENUM - which is exactly why it was made one.
