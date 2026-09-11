package com.app.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.entity.MessageOutbox;
import com.app.entity.MessageOutbox.Channel;
import com.app.repository.MessageOutboxRepository;
import com.app.service.MessagingService;

/**
 * Operating the message outbox.
 *
 * Under /admin, so ADMIN only - a test send spends provider credits, and the outbox
 * lists customer numbers and balances.
 */
@RestController
@RequestMapping("/admin/messaging")
public class MessagingController {

    private static final Logger logger = LoggerFactory.getLogger(MessagingController.class);

    @Autowired
    private MessagingService messagingService;

    @Autowired
    private MessageOutboxRepository outboxRepository;

    /**
     * Sends one message to a given number, now, and returns what the provider said.
     *
     * The way to prove the key, sender id, template and WhatsApp number are right
     * without entering a sale. It bypasses messaging.enabled deliberately - asking
     * for a test is asking for a real send - and it is recorded in the outbox like
     * anything else, so the provider's reply is kept rather than only logged.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> sendTest(@RequestBody Map<String, String> request) {
        String mobileNo = request.get("mobileNo");
        String name = request.get("name");
        String templateId = request.get("templateId");

        // "SMS" or "WHATSAPP". Omitted means the configured default, so a quick test
        // needs only a number.
        String requested = request.get("channel");
        Channel channel = requested == null || requested.isBlank()
                ? null : Channel.valueOf(requested.trim().toUpperCase());

        logger.warn("Test {} message requested for {}", requested == null ? "default" : requested, mobileNo);
        MessageOutbox result = messagingService.sendTestMessage(mobileNo, name, templateId, channel);

        return ResponseEntity.ok(Map.of(
                "id", result.getId(),
                "channel", result.getChannel().name(),
                "to", result.getRecipientMobile(),
                "status", result.getStatus().name(),
                "attempts", result.getAttempts(),
                "templateId", String.valueOf(result.getTemplateId()),
                "variables", String.valueOf(result.getVariables()),
                "body", String.valueOf(result.getBodyPreview()),
                "providerResponse", String.valueOf(result.getProviderResponse()),
                "error", String.valueOf(result.getError())));
    }

    /** The most recent messages, whatever their outcome. For a morning check. */
    @GetMapping("/outbox")
    public ResponseEntity<List<MessageOutbox>> recent(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(outboxRepository.findAll(
                org.springframework.data.domain.PageRequest.of(0, Math.min(limit, 500),
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "id"))).getContent());
    }

    /** Counts by status for one day: how many went, how many were skipped and why. */
    @GetMapping("/summary")
    public ResponseEntity<List<Object[]>> summary(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(outboxRepository.countByStatusForDate(
                date == null ? LocalDate.now() : date));
    }

    /**
     * Runs the dispatcher now rather than waiting for the schedule. Respects
     * messaging.enabled, so on a dry run it reports how many are queued and sends
     * nothing.
     */
    @PostMapping("/dispatch")
    public ResponseEntity<Map<String, Object>> dispatch() {
        int sent = messagingService.dispatchPending();
        List<MessageOutbox> pending = outboxRepository.findDispatchable(3, Limit.of(500));
        return ResponseEntity.ok(Map.of("accepted", sent, "stillQueued", pending.size()));
    }
}
