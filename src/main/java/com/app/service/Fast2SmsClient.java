package com.app.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.app.config.MessagingProperties;
import com.app.entity.MessageOutbox;
import com.app.entity.MessageOutbox.Channel;
import com.app.dto.messaging.WhatsAppTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
    private static final String TEMPLATES_ENDPOINT = "https://www.fast2sms.com/dev/dlt_manager/whatsapp";
    private static final String WHATSAPP_LOGS_ENDPOINT = "https://www.fast2sms.com/dev/whatsapp_logs";
    private static final String DLR_ENDPOINT = "https://www.fast2sms.com/dev/dlr";

    /*
     * The media and message endpoints take Metas own shapes rather than the query-string
     * form the other calls use, so they are addressed by base + version + phone number id.
     */
    private static final String WHATSAPP_API_BASE = "https://www.fast2sms.com/dev/whatsapp";
    private static final String WHATSAPP_API_VERSION = "v26.0";

    @Autowired
    private MessagingProperties properties;

    private final ObjectMapper objectMapper = new ObjectMapper();

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

    /**
     * Uploads a document and returns the provider's media id for it.
     *
     * The statement goes to Fast2SMS as a file rather than as a URL, and that is the point
     * of doing it this way: a link would have to be publicly reachable, and the document
     * holds a customer's balance and every transaction behind it. An id is only usable by
     * the account that created it.
     *
     * POST /dev/whatsapp/{version}/{phone_number_id}/media, multipart, answering {"id":...}.
     *
     * @return the media id, or empty with the reason logged
     */
    public Optional<String> uploadDocument(byte[] content, String fileName) {
        MessagingProperties.Fast2Sms config = properties.getFast2sms();
        if (!config.isConfigured() || config.getWhatsappPhoneNumberId() == null
                || config.getWhatsappPhoneNumberId().isBlank()) {
            logger.warn("Cannot upload a document: the API key or the WhatsApp phone number id is not set");
            return Optional.empty();
        }

        // Multipart is built by hand because java.net.http has no body publisher for it,
        // and pulling in a client library for three fields is not worth the dependency.
        String boundary = "scc" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = multipartBody(boundary, content, fileName);

        String url = WHATSAPP_API_BASE + "/" + WHATSAPP_API_VERSION + "/"
                + config.getWhatsappPhoneNumberId() + "/media";

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", config.getApiKey())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("Media upload rejected with HTTP {}: {}", response.statusCode(), response.body());
                return Optional.empty();
            }

            String id = objectMapper.readTree(response.body()).path("id").asText(null);
            if (id == null || id.isBlank()) {
                logger.warn("Media upload returned no id: {}", response.body());
                return Optional.empty();
            }

            logger.info("Uploaded {} ({} bytes) as media {}", fileName, content.length, id);
            return Optional.of(id);

        } catch (IOException e) {
            logger.warn("Media upload transport failure for {}: {}", fileName, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Sends a template whose header carries a document - the weekly statement.
     *
     * A different endpoint and a different shape from the ordinary send: the query-string
     * form used above has nowhere to put a media reference, so this posts Meta's own
     * message JSON to /messages. The document is referenced by the id from
     * {@link #uploadDocument}, and the body variables follow in order.
     */
    public SendResult sendDocumentTemplate(MessageOutbox message, String templateName,
                                           String mediaId, String fileName) {
        MessagingProperties.Fast2Sms config = properties.getFast2sms();
        if (!config.isConfigured() || config.getWhatsappPhoneNumberId() == null
                || config.getWhatsappPhoneNumberId().isBlank()) {
            return SendResult.rejected(
                    "Fast2SMS is not configured for WhatsApp. Set the API key and phone number id.", null);
        }

        /*
         * By name, not by the numeric message_id the other endpoint uses.
         *
         * This endpoint is Meta's own, and Meta identifies a template by name - so the
         * caller resolves it from the provider's template list. The outbox row still stores
         * the numeric id, which is what the account's own dashboard shows.
         */
        if (templateName == null || templateName.isBlank()) {
            return SendResult.rejected(
                    "A document template must be sent by name, and none was resolved.", null);
        }

        String url = WHATSAPP_API_BASE + "/" + WHATSAPP_API_VERSION + "/"
                + config.getWhatsappPhoneNumberId() + "/messages";

        try {
            ObjectNode payload = objectMapper.createObjectNode();
            payload.put("messaging_product", "whatsapp");
            payload.put("recipient_type", "individual");
            // Meta wants the country code. The stored number is ten digits.
            payload.put("to", "91" + message.getRecipientMobile());
            payload.put("type", "template");

            ObjectNode template = payload.putObject("template");
            template.put("name", templateName);
            template.putObject("language").put("code", "en");

            ArrayNode components = template.putArray("components");

            ObjectNode header = components.addObject();
            header.put("type", "header");
            ObjectNode headerParam = header.putArray("parameters").addObject();
            headerParam.put("type", "document");
            ObjectNode document = headerParam.putObject("document");
            document.put("id", mediaId);
            // The filename is what the customer sees on the attachment in WhatsApp, so it
            // says whose statement it is rather than a provider id.
            document.put("filename", fileName);

            ObjectNode bodyComponent = components.addObject();
            bodyComponent.put("type", "body");
            ArrayNode parameters = bodyComponent.putArray("parameters");
            for (String value : splitVariables(message.getVariables())) {
                parameters.addObject().put("type", "text").put("text", value);
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", config.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String responseBody = response.body() == null ? "" : response.body().trim();

            if (response.statusCode() != 200) {
                return SendResult.rejected("HTTP " + response.statusCode(), responseBody);
            }

            // This endpoint answers with Meta's shape - {"messages":[{"id":"wamid..."}]} -
            // not the {"return":true,"request_id":...} the query-string endpoint returns.
            String wamid = objectMapper.readTree(responseBody)
                    .path("messages").path(0).path("id").asText(null);
            if (wamid == null || wamid.isBlank()) {
                return SendResult.rejected("Provider returned no message id", responseBody);
            }
            return SendResult.accepted(wamid, responseBody);

        } catch (IOException e) {
            logger.warn("Document template transport failure for outbox {}: {}",
                    message.getId(), e.getMessage());
            return SendResult.rejected("Transport failure: " + e.getMessage(), null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.rejected("Interrupted while sending", null);
        }
    }

    /** Pipe-separated, the same convention the query-string endpoint uses. */
    private static List<String> splitVariables(String variables) {
        if (variables == null || variables.isBlank()) {
            return List.of();
        }
        return List.of(variables.split("\\|", -1));
    }

    /** One file plus the two fixed fields, as multipart/form-data. */
    private static byte[] multipartBody(String boundary, byte[] content, String fileName) {
        String prefix = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"messaging_product\"\r\n\r\nwhatsapp\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"type\"\r\n\r\napplication/pdf\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n";
        String suffix = "\r\n--" + boundary + "--\r\n";

        byte[] head = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] tail = suffix.getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + content.length + tail.length];

        System.arraycopy(head, 0, body, 0, head.length);
        System.arraycopy(content, 0, body, head.length, content.length);
        System.arraycopy(tail, 0, body, head.length + content.length, tail.length);
        return body;
    }

    /**
     * The WhatsApp templates that actually exist on the account.
     *
     * GET /dev/dlt_manager/whatsapp?type=template. Worth reading rather than
     * assuming: the account holds three approved templates taking 3, 3 and 1
     * variables, all UTILITY, all carrying Marathi text under an "en" language tag,
     * and two of them with a call button. None of that was knowable from the
     * constants the code used to carry.
     *
     * Returns an empty list rather than throwing - this is used to check and display
     * configuration, and a provider outage should not stop the application.
     */
    public List<WhatsAppTemplate> fetchTemplates() {
        MessagingProperties.Fast2Sms config = properties.getFast2sms();
        if (!config.isConfigured()) {
            logger.warn("Cannot list WhatsApp templates: FAST2SMS_API_KEY is not set");
            return List.of();
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(TEMPLATES_ENDPOINT + "?type=template"))
                    .timeout(Duration.ofSeconds(30))
                    // This endpoint takes the key as a header, unlike the send
                    // endpoints, which take it as a query parameter.
                    .header("Authorization", config.getApiKey())
                    .header("accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                logger.warn("Listing WhatsApp templates returned HTTP {}", response.statusCode());
                return List.of();
            }
            return parseTemplates(response.body());

        } catch (IOException e) {
            logger.warn("Could not list WhatsApp templates: {}", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (RuntimeException e) {
            logger.error("Could not parse the WhatsApp template list", e);
            return List.of();
        }
    }

    private List<WhatsAppTemplate> parseTemplates(String body) throws IOException {
        List<WhatsAppTemplate> templates = new ArrayList<>();
        JsonNode root = objectMapper.readTree(body);

        for (JsonNode account : root.path("data")) {
            for (JsonNode node : account.path("templates")) {
                WhatsAppTemplate template = new WhatsAppTemplate();
                template.setMessageId(node.path("message_id").isMissingNode()
                        ? null : node.path("message_id").asInt());
                template.setTemplateId(node.path("template_id").asText(null));
                template.setTemplateName(node.path("template_name").asText(null));
                template.setCategory(node.path("category").asText(null));
                template.setStatus(node.path("status").asText(null));
                template.setLanguage(node.path("language").asText(null));
                template.setVarCount(node.path("var_count").isMissingNode()
                        ? null : node.path("var_count").asInt());

                // The body is one entry in a components array that also holds
                // buttons and, for a document template, a header.
                for (JsonNode component : node.path("components")) {
                    String type = component.path("type").asText("");
                    if ("BODY".equalsIgnoreCase(type)) {
                        template.setBodyText(component.path("text").asText(null));
                    } else if ("BUTTONS".equalsIgnoreCase(type)) {
                        template.setHasButtons(true);
                    }
                }
                templates.add(template);
            }
        }
        return templates;
    }

    /** One message's outcome as the provider reports it. */
    public record DeliveryReport(String requestId, String status, String recipient,
                                String timestamp, String error) {
    }

    /**
     * WhatsApp delivery for a date range.
     *
     * GET /dev/whatsapp_logs?from=&to=. The provider keeps three days, and the logs
     * cannot be filtered by request_id - the whole range comes back and is matched
     * locally against provider_message_id, which is why that column is stored.
     */
    public List<DeliveryReport> fetchWhatsappDelivery(LocalDate from, LocalDate to) {
        if (!properties.getFast2sms().isConfigured()) {
            return List.of();
        }

        String url = WHATSAPP_LOGS_ENDPOINT + "?from=" + from + "&to=" + to;
        return fetchReports(url, node -> new DeliveryReport(
                node.path("request_id").asText(null),
                node.path("status").asText(null),
                node.path("recipient_id").asText(null),
                node.path("timestamp").asText(null),
                node.path("errors").isNull() ? null : node.path("errors").asText(null)));
    }

    /**
     * One SMS's delivery report, by request id.
     *
     * GET /dev/dlr/{request_id}. Per-message rather than a date range, so SMS is
     * polled one row at a time - acceptable at this volume, roughly thirty a day.
     *
     * The response shape is not documented in detail, so several plausible field
     * names are tried rather than assuming one and silently reading nothing.
     */
    public Optional<DeliveryReport> fetchSmsDelivery(String requestId) {
        if (!properties.getFast2sms().isConfigured() || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }

        try {
            HttpResponse<String> response = get(DLR_ENDPOINT + "/" + URLEncoder.encode(requestId, StandardCharsets.UTF_8));
            if (response == null || response.statusCode() != 200) {
                return Optional.empty();
            }

            JsonNode root = objectMapper.readTree(response.body());
            // The report may be the root object, or the first entry of a data array.
            JsonNode node = root.path("data").isArray() && !root.path("data").isEmpty()
                    ? root.path("data").get(0)
                    : root;

            String status = firstText(node, "status", "delivery_status", "dlr_status", "message_status");
            if (status == null) {
                logger.debug("No recognisable status field in the delivery report for {}", requestId);
                return Optional.empty();
            }

            return Optional.of(new DeliveryReport(
                    requestId,
                    status,
                    firstText(node, "number", "recipient", "mobile"),
                    firstText(node, "timestamp", "delivered_on", "date_time", "updated_at"),
                    firstText(node, "error", "errors", "reason")));

        } catch (IOException e) {
            logger.warn("Could not fetch the SMS delivery report for {}: {}", requestId, e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            logger.warn("Could not read the SMS delivery report for {}: {}", requestId, e.getMessage());
            return Optional.empty();
        }
    }

    /** The first of these fields that carries a value, or null. */
    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private List<DeliveryReport> fetchReports(String url,
                                              java.util.function.Function<JsonNode, DeliveryReport> mapper) {
        try {
            HttpResponse<String> response = get(url);
            if (response == null || response.statusCode() != 200) {
                logger.warn("Delivery log request returned {}", response == null ? "no response" : response.statusCode());
                return List.of();
            }

            List<DeliveryReport> reports = new ArrayList<>();
            for (JsonNode node : objectMapper.readTree(response.body()).path("data")) {
                reports.add(mapper.apply(node));
            }
            return reports;

        } catch (IOException e) {
            logger.warn("Could not fetch delivery logs: {}", e.getMessage());
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (RuntimeException e) {
            logger.error("Could not read the delivery logs", e);
            return List.of();
        }
    }

    /** A GET with the key in the Authorization header, as the log endpoints expect. */
    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", properties.getFast2sms().getApiKey())
                .header("accept", "application/json")
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
