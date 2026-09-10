package com.app.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.app.config.MessagingProperties;
import com.app.entity.MessageOutbox;
import com.app.entity.MessageOutbox.Channel;

/**
 * Talks to Fast2SMS. Nothing else in the application knows the provider exists.
 *
 * Replaces the HttpsURLConnection blocks in SendSmsService, which built their URLs
 * from hardcoded constants, swallowed non-IO exceptions, and reported success on the
 * strength of an HTTP 200 - Fast2SMS answers 200 with {"return":false} when it
 * rejects a message, so a rejected send was logged as sent.
 *
 * This returns the outcome rather than logging it. Whether to retry, and what to
 * record, is the dispatcher's decision.
 */
@Service
public class Fast2SmsClient {

    private static final Logger logger = LoggerFactory.getLogger(Fast2SmsClient.class);

    private static final String SMS_ENDPOINT = "https://www.fast2sms.com/dev/bulkV2";
    private static final String WHATSAPP_ENDPOINT = "https://www.fast2sms.com/dev/whatsapp";

    @Autowired
    private MessagingProperties properties;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** What the provider said, and whether it accepted the message. */
    public record SendResult(boolean accepted, String messageId, String response, String error) {

        public static SendResult accepted(String messageId, String response) {
            return new SendResult(true, messageId, response, null);
        }

        public static SendResult rejected(String error, String response) {
            return new SendResult(false, null, response, error);
        }
    }

    /**
     * Sends one message. Never throws: a transport failure comes back as a rejected
     * result so the caller records it against the outbox row like any other.
     */
    public SendResult send(MessageOutbox message) {
        if (!properties.getFast2sms().isConfigured()) {
            return SendResult.rejected(
                    "Fast2SMS is not configured. Set FAST2SMS_API_KEY in .env.", null);
        }

        try {
            String url = message.getChannel() == Channel.WHATSAPP
                    ? whatsappUrl(message)
                    : smsUrl(message);

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("cache-control", "no-cache")
                    .header("accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body() == null ? "" : response.body().trim();

            if (response.statusCode() != 200) {
                return SendResult.rejected("HTTP " + response.statusCode(), body);
            }

            // Fast2SMS answers 200 with {"return":false,"message":[...]} on a
            // rejection, so the status code alone is not the answer. The old code
            // treated any 200 as sent.
            if (!looksAccepted(body)) {
                return SendResult.rejected("Provider rejected the message", body);
            }

            return SendResult.accepted(extractRequestId(body), body);

        } catch (IOException e) {
            // Worth retrying - the network or the provider was briefly unavailable.
            logger.warn("Fast2SMS transport failure for outbox {}: {}", message.getId(), e.getMessage());
            return SendResult.rejected("Transport failure: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.rejected("Interrupted while sending", null);
        } catch (RuntimeException e) {
            logger.error("Fast2SMS send failed for outbox {}", message.getId(), e);
            return SendResult.rejected(e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        }
    }

    private String smsUrl(MessageOutbox message) {
        MessagingProperties.Fast2Sms config = properties.getFast2sms();
        String template = message.getTemplateId() == null ? config.getSmsTemplateId() : message.getTemplateId();

        return SMS_ENDPOINT
                + "?authorization=" + encode(config.getApiKey())
                + "&route=dlt"
                + "&sender_id=" + encode(config.getSenderId())
                + "&message=" + encode(template)
                + "&variables_values=" + encode(message.getVariables())
                + "&flash=0"
                + "&numbers=" + encode(message.getRecipientMobile());
    }

    private String whatsappUrl(MessageOutbox message) {
        MessagingProperties.Fast2Sms config = properties.getFast2sms();
        String template = message.getTemplateId() == null
                ? config.getWhatsappSaleTemplateId() : message.getTemplateId();

        return WHATSAPP_ENDPOINT
                + "?authorization=" + encode(config.getApiKey())
                + "&message_id=" + encode(template)
                + "&phone_number_id=" + encode(config.getWhatsappPhoneNumberId())
                + "&numbers=" + encode(message.getRecipientMobile())
                + "&variables_values=" + encode(message.getVariables());
    }

    /**
     * Fast2SMS returns {"return":true,...} when it accepts. Read as a substring
     * rather than parsed: the response shape differs between the SMS and WhatsApp
     * endpoints, and a missing field should not be read as success.
     */
    private boolean looksAccepted(String body) {
        String normalised = body.replaceAll("\\s", "").toLowerCase();
        return normalised.contains("\"return\":true");
    }

    /** The provider's own id for the message, when it gives one. */
    private String extractRequestId(String body) {
        int marker = body.indexOf("\"request_id\"");
        if (marker < 0) {
            return null;
        }
        int start = body.indexOf('"', body.indexOf(':', marker) + 1);
        int end = start < 0 ? -1 : body.indexOf('"', start + 1);
        return start < 0 || end < 0 ? null : body.substring(start + 1, end);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
