package com.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Limit;

import com.app.config.MessagingProperties;
import com.app.entity.Customer;
import com.app.entity.MessageOutbox;
import com.app.entity.MessageOutbox.Channel;
import com.app.entity.MessageOutbox.MessageType;
import com.app.entity.MessageOutbox.Status;
import com.app.repository.MessageOutboxRepository;
import com.app.service.Fast2SmsClient.SendResult;

/**
 * The three gates every message passes before a provider is called, and the two
 * properties the outbox exists to give: nothing is sent twice, and everything that
 * was attempted left a record.
 *
 * The gates matter because of what is being sent next. Today's SMS says a sale was
 * recorded; a statement says what the customer owes. In this database 130 customers
 * have no usable number and one number is shared by seven of them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MessagingServiceTest {

    @Mock
    private MessageOutboxRepository outboxRepository;

    @Mock
    private Fast2SmsClient fast2SmsClient;

    private MessagingProperties properties;

    @InjectMocks
    private MessagingService messagingService;

    private Customer customer;

    @BeforeEach
    void setUp() {
        properties = new MessagingProperties();
        properties.setEnabled(true);
        properties.setChannel(Channel.SMS);
        properties.setMaxAttempts(3);
        properties.setBatchSize(50);
        properties.getFast2sms().setApiKey("test-key");

        // Field injection, so the properties object has to be placed by hand.
        org.springframework.test.util.ReflectionTestUtils
                .setField(messagingService, "properties", properties);

        customer = new Customer();
        customer.setId(67L);
        customer.setName("Javed Kureshi");
        customer.setMobileNo("9975080207");
        customer.setBalanceAmount(new BigDecimal("307940.00"));

        when(outboxRepository.save(any(MessageOutbox.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(outboxRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
    }

    private MessageOutbox enqueueFor(Customer target) {
        return enqueueFor(target, properties.getChannel());
    }

    private MessageOutbox enqueueFor(Customer target, Channel channel) {
        return messagingService.enqueue(target, channel, MessageType.DAILY_SALE_SUMMARY,
                LocalDate.parse("2026-09-09"), null, "vars", "body");
    }

    @Test
    @DisplayName("a queued message is PENDING and records where it is going")
    void queuesPending() {
        MessageOutbox message = enqueueFor(customer);

        assertEquals(Status.PENDING, message.getStatus());
        assertEquals("9975080207", message.getRecipientMobile());
        assertEquals("Javed Kureshi", message.getRecipientName());
        assertEquals(Channel.SMS, message.getChannel());
        assertEquals("DAILY_SALE_SUMMARY:SMS:67:2026-09-09", message.getIdempotencyKey());
        assertEquals(0, message.getAttempts());
    }

    @Test
    @DisplayName("the same message is never queued twice")
    void isIdempotent() {
        MessageOutbox first = new MessageOutbox();
        first.setId(1L);
        first.setStatus(Status.SENT);
        when(outboxRepository.findByIdempotencyKey("DAILY_SALE_SUMMARY:SMS:67:2026-09-09"))
                .thenReturn(Optional.of(first));

        MessageOutbox result = enqueueFor(customer);

        // The existing row comes back; nothing new is written. This is what makes a
        // re-run, or a back-dated sale for a day already messaged, harmless.
        assertEquals(1L, result.getId());
        assertEquals(Status.SENT, result.getStatus());
        verify(outboxRepository, never()).save(any(MessageOutbox.class));
    }

    @Test
    @DisplayName("an unusable number is recorded as skipped, with the reason")
    void skipsBadNumbers() {
        customer.setMobileNo("1234567890");
        MessageOutbox placeholder = enqueueFor(customer);
        assertEquals(Status.SKIPPED, placeholder.getStatus());
        assertTrue(placeholder.getSkipReason().contains("placeholder"), placeholder.getSkipReason());

        customer.setMobileNo("");
        assertEquals(Status.SKIPPED, enqueueFor(customer).getStatus());

        customer.setMobileNo("909697601");
        MessageOutbox tooShort = enqueueFor(customer);
        assertEquals(Status.SKIPPED, tooShort.getStatus());
        assertTrue(tooShort.getSkipReason().contains("10 digits"), tooShort.getSkipReason());
    }

    @Test
    @DisplayName("WhatsApp needs consent; SMS does not ask for it")
    void whatsappRequiresOptIn() {
        properties.setChannel(Channel.WHATSAPP);

        // No opt-in recorded - which is every existing customer.
        MessageOutbox notOptedIn = enqueueFor(customer);
        assertEquals(Status.SKIPPED, notOptedIn.getStatus());
        assertTrue(notOptedIn.getSkipReason().contains("not opted in"), notOptedIn.getSkipReason());

        customer.setWhatsappOptInAt(LocalDateTime.now());
        assertEquals(Status.PENDING, enqueueFor(customer).getStatus());

        // Opting out overrides an earlier opt-in, and is never cleared automatically.
        customer.setWhatsappOptOut(true);
        MessageOutbox optedOut = enqueueFor(customer);
        assertEquals(Status.SKIPPED, optedOut.getStatus());
        assertTrue(optedOut.getSkipReason().contains("opted out"), optedOut.getSkipReason());

        // The same customer, no consent at all, is fine for SMS - a different and
        // much smaller disclosure than a statement.
        properties.setChannel(Channel.SMS);
        customer.setWhatsappOptOut(true);
        customer.setWhatsappOptInAt(null);
        assertEquals(Status.PENDING, enqueueFor(customer).getStatus());
    }

    @Test
    @DisplayName("with messaging disabled nothing is sent, and everything is still recorded")
    void dryRunSendsNothing() {
        properties.setEnabled(false);

        MessageOutbox queued = new MessageOutbox();
        queued.setId(1L);
        queued.setStatus(Status.PENDING);
        when(outboxRepository.findDispatchable(anyInt(), any(Limit.class))).thenReturn(List.of(queued));

        assertEquals(0, messagingService.dispatchPending());

        // The point of the dry run: the row survives to be read, and the provider is
        // never called.
        verify(fast2SmsClient, never()).send(any(MessageOutbox.class));
        assertEquals(Status.PENDING, queued.getStatus());
    }

    @Test
    @DisplayName("a send records the provider's id, and a rejection records the reason")
    void recordsOutcomes() {
        MessageOutbox message = new MessageOutbox();
        message.setId(5L);
        message.setStatus(Status.PENDING);
        message.setRecipientMobile("9975080207");
        when(outboxRepository.findById(5L)).thenReturn(Optional.of(message));

        when(fast2SmsClient.send(message))
                .thenReturn(SendResult.accepted("req-123", "{\"return\":true}"));
        assertTrue(messagingService.sendOne(message));

        assertEquals(Status.SENT, message.getStatus());
        assertEquals("req-123", message.getProviderMessageId());
        assertEquals(1, message.getAttempts());
        assertNotNull(message.getSentAt());
        assertNull(message.getError());

        // A rejection is a failure with the reason kept, not a lost log line.
        MessageOutbox failing = new MessageOutbox();
        failing.setId(6L);
        failing.setStatus(Status.PENDING);
        failing.setRecipientMobile("9975080207");
        when(outboxRepository.findById(6L)).thenReturn(Optional.of(failing));
        when(fast2SmsClient.send(failing))
                .thenReturn(SendResult.rejected("Provider rejected the message", "{\"return\":false}"));

        assertTrue(!messagingService.sendOne(failing));
        assertEquals(Status.FAILED, failing.getStatus());
        assertEquals(1, failing.getAttempts());
        assertTrue(failing.getError().contains("rejected"), failing.getError());
        assertNull(failing.getSentAt());
    }

    @Test
    @DisplayName("a message already sent is not sent again by a later batch")
    void doesNotResendSentMessages() {
        MessageOutbox alreadySent = new MessageOutbox();
        alreadySent.setId(7L);
        alreadySent.setStatus(Status.SENT);
        when(outboxRepository.findById(7L)).thenReturn(Optional.of(alreadySent));

        assertTrue(!messagingService.sendOne(alreadySent));
        verify(fast2SmsClient, never()).send(any(MessageOutbox.class));
    }

    @Test
    @DisplayName("the retry cap is the configured one")
    void honoursTheAttemptCap() {
        properties.setMaxAttempts(5);
        when(outboxRepository.findDispatchable(anyInt(), any(Limit.class))).thenReturn(List.of());

        messagingService.dispatchPending();

        ArgumentCaptor<Integer> attempts = ArgumentCaptor.forClass(Integer.class);
        verify(outboxRepository).findDispatchable(attempts.capture(), any(Limit.class));
        assertEquals(5, attempts.getValue());
    }
}
