package com.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

import com.app.entity.Customer;
import com.app.entity.CustomerLedger;
import com.app.entity.CustomerLedger.TransactionType;
import com.app.entity.CustomerPayment;
import com.app.repository.CustomerLedgerRepository;
import com.app.repository.CustomerRepository;
import com.app.repository.SaleRepository;

/**
 * Cancelling a payment has to put the debt back.
 *
 * The previous implementation soft-deleted the payment row and then called
 * recalculateBalancesFromDate, which re-chains the ledger rows that exist -
 * including the cancelled payment's credit. The balance stayed reduced and the
 * statement still showed a receipt that had been withdrawn. No payment has ever
 * been recorded in this system, so nothing was corrupted, but the path was wrong.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentReversalTest {

    @Mock
    private CustomerLedgerRepository ledgerRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private SaleRepository saleRepository;

    @InjectMocks
    private LedgerServiceImpl ledgerService;

    private Customer customer;
    private CustomerPayment payment;

    @BeforeEach
    void setUp() {
        customer = new Customer();
        customer.setId(42L);
        customer.setName("Imran Poultry");

        payment = new CustomerPayment();
        payment.setId(77L);
        payment.setCustomer(customer);
        payment.setPaymentDate(LocalDate.parse("2026-09-05"));
        payment.setAmount(new BigDecimal("10000.00"));
        payment.setPaymentMode("UPI");
        payment.setTransactionReference("UPI-8891");

        when(ledgerRepository.save(any(CustomerLedger.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private CustomerLedger row(long id, String date, String debit, String credit, String balance) {
        CustomerLedger entry = new CustomerLedger();
        entry.setId(id);
        entry.setCustomer(customer);
        entry.setTransactionDate(LocalDate.parse(date));
        entry.setDebitAmount(new BigDecimal(debit));
        entry.setCreditAmount(new BigDecimal(credit));
        entry.setRunningBalance(new BigDecimal(balance));
        return entry;
    }

    @Test
    @DisplayName("a cancelled payment is reversed by a debit for the same amount and date")
    void reversalPostsAMatchingDebit() {
        // Mutable: the recalculation sorts the list it is given, and Spring Data
        // hands back an ArrayList. List.of() would throw on sort even when empty.
        when(ledgerRepository.findByCustomerAndTransactionDateGreaterThanEqualOrderByTransactionDateAsc(
                eq(customer), any(LocalDate.class))).thenReturn(new ArrayList<>());
        when(ledgerRepository.findLatestBefore(eq(customer), any(LocalDate.class), any(Limit.class)))
                .thenReturn(List.of());

        ledgerService.reversePaymentLedgerEntry(payment);

        ArgumentCaptor<CustomerLedger> captor = ArgumentCaptor.forClass(CustomerLedger.class);
        verify(ledgerRepository).save(captor.capture());
        CustomerLedger reversal = captor.getValue();

        assertEquals(TransactionType.DEBIT_NOTE, reversal.getTransactionType());
        assertEquals(new BigDecimal("10000.00"), reversal.getDebitAmount());
        assertEquals(BigDecimal.ZERO.setScale(2), reversal.getCreditAmount());
        // Same date as the receipt, so it lands in the right place in history.
        assertEquals(LocalDate.parse("2026-09-05"), reversal.getTransactionDate());
        assertEquals("PAYMENT_REVERSAL", reversal.getReferenceType());
        assertEquals(77L, reversal.getReferenceId());
        assertTrue(reversal.getDescription().contains("77"), reversal.getDescription());
        assertTrue(reversal.getDescription().contains("UPI-8891"), reversal.getDescription());
    }

    @Test
    @DisplayName("the balance returns to what it was before the receipt")
    void reversalRestoresTheBalance() {
        // A sale of 18,500 leaving 18,500 owed, then a 10,000 receipt taking it to
        // 8,500. Cancelling the receipt must take it back to 18,500.
        CustomerLedger sale = row(1, "2026-09-02", "18500.00", "0.00", "18500.00");
        CustomerLedger receipt = row(2, "2026-09-05", "0.00", "10000.00", "8500.00");

        when(ledgerRepository.findLatestBefore(eq(customer), eq(LocalDate.parse("2026-09-05")), any(Limit.class)))
                .thenReturn(List.of(sale));
        when(ledgerRepository.findByCustomerAndTransactionDateGreaterThanEqualOrderByTransactionDateAsc(
                eq(customer), eq(LocalDate.parse("2026-09-05"))))
                .thenAnswer(invocation -> {
                    // What the repository would return after the reversal is saved.
                    CustomerLedger reversal = row(3, "2026-09-05", "10000.00", "0.00", "0.00");
                    return new ArrayList<>(List.of(receipt, reversal));
                });

        ledgerService.reversePaymentLedgerEntry(payment);

        assertEquals(new BigDecimal("18500.00"), customer.getBalanceAmount());
        verify(customerRepository).save(customer);
    }

    @Test
    @DisplayName("the opening figure is read as one row, not by loading the whole history")
    void recalculationReadsOnlyThePreviousRow() {
        when(ledgerRepository.findLatestBefore(eq(customer), any(LocalDate.class), any(Limit.class)))
                .thenReturn(List.of(row(1, "2026-08-31", "5000.00", "0.00", "12000.00")));
        when(ledgerRepository.findByCustomerAndTransactionDateGreaterThanEqualOrderByTransactionDateAsc(
                eq(customer), any(LocalDate.class)))
                .thenReturn(new ArrayList<>(List.of(
                        row(2, "2026-09-01", "3000.00", "0.00", "0.00"),
                        row(3, "2026-09-02", "0.00", "1000.00", "0.00"))));

        ledgerService.recalculateBalancesFromDate(customer, LocalDate.parse("2026-09-01"));

        // 12,000 + 3,000 - 1,000. Chained from the single prior row.
        assertEquals(new BigDecimal("14000.00"), customer.getBalanceAmount());

        // The old implementation called this with 1900-01-01 to find one number.
        verify(ledgerRepository, never())
                .findByCustomerAndTransactionDateBetweenOrderByTransactionDateAsc(
                        any(Customer.class), eq(LocalDate.of(1900, 1, 1)), any(LocalDate.class));
    }
}
