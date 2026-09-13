package com.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.app.entity.MessageOutbox.Channel;

import lombok.Data;

/**
 * Messaging configuration, and the Fast2SMS credentials.
 *
 * These were constants in SendSmsService - the API key in plain text on line 30 and
 * again on line 38, committed since the baseline and still in git history. Anyone
 * with repository access could send on the account and spend its credits. They come
 * from .env now, like the database password and the JWT secret.
 *
 * The key needs rotating regardless: moving it out of the source does not remove it
 * from the history.
 */
@Component
@ConfigurationProperties(prefix = "messaging")
@Data
public class MessagingProperties {

    /**
     * Whether messages actually reach the provider.
     *
     * Off by default, and that is deliberate rather than cautious boilerplate. With
     * it off, the outbox is still written exactly as it would be - recipient,
     * template, variables, the readable body - and every row is left PENDING. That
     * makes a dry run possible: queue a day's messages, read what would have gone
     * where, and only then turn it on. Given that 130 customer numbers are wrong
     * and one number is shared by seven customers, seeing the list first is worth
     * more than sending a day earlier.
     */
    private boolean enabled = false;

    /**
     * Whether the weekly statement run happens at all.
     *
     * Separate from messaging.enabled, which governs sending. This governs whether the
     * Monday job builds the week's statements in the first place - so the queue can be
     * filled and inspected before the send switch is touched, and a week's statements can
     * be turned off without stopping the daily message.
     */
    private boolean weeklyStatementEnabled = false;

    /** Time zone the weekly cron runs in. The business is in IST. */
    private String timezone = "Asia/Kolkata";

    /** SMS today; WHATSAPP once the templates are approved. */
    private Channel channel = Channel.SMS;

    /** Attempts before a message is left alone for someone to look at. */
    private int maxAttempts = 3;

    /** Messages taken per dispatch run, so one run cannot monopolise the app. */
    private int batchSize = 50;

    private final Fast2Sms fast2sms = new Fast2Sms();

    @Data
    public static class Fast2Sms {
        /** Authorization key. Shared by the SMS and WhatsApp endpoints. */
        private String apiKey;

        /** DLT sender id, six characters, registered with the operator. */
        private String senderId = "SHLCKN";

        /** Approved DLT template for the daily message. */
        private String smsTemplateId = "195555";

        /** WhatsApp business number id on the Fast2SMS account. */
        private String whatsappPhoneNumberId;

        /** Approved WhatsApp template ids. */
        private String whatsappSaleTemplateId = "12082";
        private String whatsappPaymentTemplateId = "12083";

        /**
         * The eight-variable daily summary template.
         *
         * Deliberately blank until it is approved, and separate from
         * whatsappSaleTemplateId, which takes three. Sending eight values to a
         * three-variable template is a rejection, so an unset id means the daily
         * WhatsApp message is queued and skipped with a reason rather than sent
         * against the wrong template and quietly failing for every customer.
         */
        private String whatsappDailyTemplateId;

        /**
         * Whether the daily message carries the per-kilo rate.
         *
         * Off, because the owner does not want the rate on a customer's phone. It
         * decides which builder runs, and therefore how many variables are sent:
         * seven without the rate, eight with it. The two need different approved
         * templates, so this must agree with whatsappDailyTemplateId -
         *
         *   false -> daily_sale_no_rate  (message_id 32367, 7 variables)
         *   true  -> daily_sale_summary  (message_id 32340, 8 variables)
         *
         * A mismatch is caught before anything is sent: the queue checks the value
         * count against the provider's own var_count for the configured template and
         * holds the message with a reason. So getting this wrong delays messages
         * rather than sending a balance in the slot meant for a rate.
         */
        private boolean whatsappDailyIncludesRate = false;

        /** The media template that carries the weekly statement PDF. */
        private String whatsappStatementTemplateId;

        public boolean hasWhatsappDailyTemplate() {
            return whatsappDailyTemplateId != null && !whatsappDailyTemplateId.isBlank();
        }

        public boolean isConfigured() {
            return apiKey != null && !apiKey.isBlank();
        }
    }
}
