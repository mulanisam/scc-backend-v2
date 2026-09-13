package com.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.app.dto.CustomerPaymentDTO;
import com.app.dto.TradingPaymentDto;
import com.app.dto.payment.PaymentView;
import com.app.entity.Customer;
import com.app.entity.CustomerPayment;
import com.app.entity.Party;
import com.app.exception.ResourceNotFoundException;
import com.app.repository.PartyRepository;

/**
 * Money received from a wholesale party.
 *
 * This path did not exist until now, and its absence was not visible: the trading screen
 * posted every party payment to /trading/payment, which no controller mapped, so the request
 * 404'd and the screen said "Error creating payment entry". The money had been taken and the
 * balance never moved.
 *
 * What the tests hold in place is the decision that a party receipt is a customer receipt.
 * A party's account is a customer ledger account - route 9's ₹44,03,490 sits in
 * customer_ledger - so this must delegate to the same payment service rather than write its
 * own row. If it ever stops delegating, a party has two answers to what they have paid and
 * the statement shows whichever one it happens to read.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PartyPaymentTest {

    @Mock
    private PartyRepository partyRepository;

    @Mock
    private PaymentService paymentService;

    @InjectMocks
    private TradingServiceImpl tradingService;

    private static Party party(Long id, String name, Customer customer) {
        Party party = new Party();
        party.setId(id);
        party.setName(name);
        party.setCustomer(customer);
        return party;
    }

    private static Customer customer(Long id, String name) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        return customer;
    }

    private static TradingPaymentDto request() {
        TradingPaymentDto dto = new TradingPaymentDto();
        dto.setPartyId(5L);
        dto.setDate(LocalDate.now().minusDays(2));
        dto.setPayment(new BigDecimal("50000"));
        dto.setPaymentMode("cash");
        dto.setTransactionId("CHQ-4417");
        dto.setDescription("Settled in part");
        return dto;
    }

    /** The saved receipt the payment service would hand back. */
    private static CustomerPayment saved(Customer customer, BigDecimal amount) {
        CustomerPayment payment = new CustomerPayment();
        payment.setId(77L);
        payment.setCustomer(customer);
        payment.setAmount(amount);
        payment.setPaymentDate(LocalDate.now().minusDays(2));
        payment.setPaymentMode("CASH");
        return payment;
    }

    @Test
    @DisplayName("The receipt is credited to the party's own ledger account")
    void creditsThePartysCustomerAccount() {
        Customer account = customer(489L, "Maqsud Shaikh");
        when(partyRepository.findById(5L)).thenReturn(Optional.of(party(5L, "Maqsud Shaikh", account)));
        when(paymentService.createPayment(any())).thenAnswer(invocation -> {
            CustomerPaymentDTO dto = invocation.getArgument(0);
            return saved(account, dto.getAmount());
        });

        PaymentView view = tradingService.createPartyPayment(request());

        ArgumentCaptor<CustomerPaymentDTO> sent = ArgumentCaptor.forClass(CustomerPaymentDTO.class);
        verify(paymentService).createPayment(sent.capture());

        // The customer behind the party, not the party id - crediting party 5 as customer 5
        // would move a completely unrelated account's balance.
        assertEquals(489L, sent.getValue().getCustomerId());
        assertEquals(new BigDecimal("50000"), sent.getValue().getAmount());
        assertEquals("CHQ-4417", sent.getValue().getTransactionReference());
        assertEquals("Settled in part", sent.getValue().getRemarks());

        assertEquals(77L, view.id());
        assertEquals("Maqsud Shaikh", view.customerName());
    }

    @Test
    @DisplayName("The payment mode is upper-cased, so reports do not split it in two")
    void normalisesThePaymentMode() {
        Customer account = customer(489L, "Maqsud Shaikh");
        when(partyRepository.findById(5L)).thenReturn(Optional.of(party(5L, "Maqsud Shaikh", account)));
        when(paymentService.createPayment(any())).thenReturn(saved(account, new BigDecimal("50000")));

        // The trading screen sends "cash"; every other screen sends "CASH". Left as typed,
        // the reports group on the column and show two payment methods with half the money each.
        tradingService.createPartyPayment(request());

        ArgumentCaptor<CustomerPaymentDTO> sent = ArgumentCaptor.forClass(CustomerPaymentDTO.class);
        verify(paymentService).createPayment(sent.capture());
        assertEquals("CASH", sent.getValue().getPaymentMode());
    }

    @Test
    @DisplayName("A blank mode becomes CASH rather than null")
    void defaultsTheMode() {
        Customer account = customer(489L, "Maqsud Shaikh");
        when(partyRepository.findById(5L)).thenReturn(Optional.of(party(5L, "Maqsud Shaikh", account)));
        when(paymentService.createPayment(any())).thenReturn(saved(account, new BigDecimal("50000")));

        TradingPaymentDto dto = request();
        dto.setPaymentMode("   ");
        tradingService.createPartyPayment(dto);

        ArgumentCaptor<CustomerPaymentDTO> sent = ArgumentCaptor.forClass(CustomerPaymentDTO.class);
        verify(paymentService).createPayment(sent.capture());
        assertEquals("CASH", sent.getValue().getPaymentMode());
    }

    @Test
    @DisplayName("Both channels are passed through, independently")
    void passesTheChannelChoicesThrough() {
        Customer account = customer(489L, "Maqsud Shaikh");
        when(partyRepository.findById(5L)).thenReturn(Optional.of(party(5L, "Maqsud Shaikh", account)));
        when(paymentService.createPayment(any())).thenReturn(saved(account, new BigDecimal("50000")));

        TradingPaymentDto dto = request();
        dto.setSendSms(false);
        dto.setSendWhatsapp(true);
        tradingService.createPartyPayment(dto);

        ArgumentCaptor<CustomerPaymentDTO> sent = ArgumentCaptor.forClass(CustomerPaymentDTO.class);
        verify(paymentService).createPayment(sent.capture());
        // Not a channel choice: the two say different things, so one being off must not
        // turn the other off with it.
        assertTrue(sent.getValue().isSendWhatsapp());
        assertEquals(false, sent.getValue().isSendSms());
    }

    @Test
    @DisplayName("A party with no ledger account is refused, not silently credited to nobody")
    void refusesAPartyWithNoAccount() {
        when(partyRepository.findById(5L)).thenReturn(Optional.of(party(5L, "Naushad Trading", null)));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> tradingService.createPartyPayment(request()));

        assertTrue(thrown.getMessage().contains("Naushad Trading"),
                "the message must name the party, so the operator knows which one to link");
        assertTrue(thrown.getMessage().contains("ledger account"));
        verify(paymentService, never()).createPayment(any());
    }

    @Test
    @DisplayName("A future-dated receipt is refused")
    void refusesAFutureDate() {
        // A receipt dated next week sorts above every later row on the statement, so the
        // closing balance stops matching the transactions above it.
        TradingPaymentDto dto = request();
        dto.setDate(LocalDate.now().plusDays(1));

        assertThrows(IllegalArgumentException.class, () -> tradingService.createPartyPayment(dto));
        verify(paymentService, never()).createPayment(any());
    }

    @Test
    @DisplayName("Today is allowed - money is usually taken on the day it is entered")
    void allowsToday() {
        Customer account = customer(489L, "Maqsud Shaikh");
        when(partyRepository.findById(5L)).thenReturn(Optional.of(party(5L, "Maqsud Shaikh", account)));
        when(paymentService.createPayment(any())).thenReturn(saved(account, new BigDecimal("50000")));

        TradingPaymentDto dto = request();
        dto.setDate(LocalDate.now());
        tradingService.createPartyPayment(dto);

        verify(paymentService).createPayment(any());
    }

    @Test
    @DisplayName("A missing party or date is refused before anything is written")
    void refusesIncompleteRequests() {
        TradingPaymentDto noParty = request();
        noParty.setPartyId(null);
        assertThrows(IllegalArgumentException.class, () -> tradingService.createPartyPayment(noParty));

        TradingPaymentDto noDate = request();
        noDate.setDate(null);
        assertThrows(IllegalArgumentException.class, () -> tradingService.createPartyPayment(noDate));

        verify(paymentService, never()).createPayment(any());
    }

    @Test
    @DisplayName("An unknown party is a 404, not a 500")
    void refusesAnUnknownParty() {
        when(partyRepository.findById(5L)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> tradingService.createPartyPayment(request()));
        verify(paymentService, never()).createPayment(any());
    }
}
