package com.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Limit;

import com.app.entity.Purchase;
import com.app.entity.Supplier;
import com.app.entity.SupplierLedger;
import com.app.entity.SupplierLedger.TransactionType;
import com.app.entity.SupplierPaymentHist;
import com.app.repository.SupplierLedgerRepository;
import com.app.repository.SupplierRepository;

/**
 * The supplier ledger's arithmetic, and the direction it runs in.
 *
 * This is the side of the ledger that had no tests and no ledger: what the business owed a
 * supplier was a single column that createPurchase overwrote with each new purchase's total,
 * so five purchases worth 8,12,500 left 1,95,000 on record and 12,05,020 of real liability
 * appeared nowhere at all.
 *
 * <p>The thing most worth pinning down is the <em>direction</em>. A customer owes us, so their
 * balance rises on a debit; we owe a supplier, so theirs rises on a credit. Getting that
 * backwards would not crash - it would quietly report the business as being owed money by the
 * people it owes, and every figure would look plausible.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierLedgerTest {

    @Mock
    private SupplierLedgerRepository ledgerRepository;

    @Mock
    private SupplierRepository supplierRepository;

    @InjectMocks
    private SupplierLedgerService service;

    /** Stands in for the table, so balances can be asserted across several postings. */
    private List<SupplierLedger> table;
    private Supplier supplier;
    private long nextId;

    @BeforeEach
    void setUp() {
        table = new ArrayList<>();
        nextId = 1;

        supplier = new Supplier();
        supplier.setId(7L);
        supplier.setName("Akbar Poultry");
        supplier.setPendingPayment(BigDecimal.ZERO);

        when(supplierRepository.findById(7L)).thenReturn(Optional.of(supplier));
        when(supplierRepository.save(any(Supplier.class))).thenAnswer(i -> i.getArgument(0));

        when(ledgerRepository.save(any(SupplierLedger.class))).thenAnswer(invocation -> {
            SupplierLedger row = invocation.getArgument(0);
            if (row.getId() == null) {
                row.setId(nextId++);
                table.add(row);
            }
            return row;
        });

        // balanceBefore: everything strictly earlier than the given date.
        when(ledgerRepository.balanceBefore(anyLong(), any())).thenAnswer(invocation -> {
            LocalDate before = invocation.getArgument(1);
            return table.stream()
                    .filter(row -> row.getTransactionDate().isBefore(before))
                    .map(row -> row.getCreditAmount().subtract(row.getDebitAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        });

        // findLatest: newest row in statement order, which is what "what do we owe now" reads.
        when(ledgerRepository.findLatest(anyLong(), any(Limit.class))).thenAnswer(invocation ->
                table.stream()
                        .sorted(statementOrder().reversed())
                        .limit(1)
                        .toList());

        when(ledgerRepository.findFromDate(anyLong(), any())).thenAnswer(invocation -> {
            LocalDate from = invocation.getArgument(1);
            return table.stream()
                    .filter(row -> !row.getTransactionDate().isBefore(from))
                    .sorted(statementOrder())
                    .toList();
        });

        when(ledgerRepository.findAll()).thenAnswer(invocation -> List.copyOf(table));
    }

    /** Purchases before payments on a shared date, then by id - the order events happened in. */
    private static Comparator<SupplierLedger> statementOrder() {
        return Comparator.comparing(SupplierLedger::getTransactionDate)
                .thenComparing(row -> row.getTransactionType() == TransactionType.PURCHASE ? 0 : 1)
                .thenComparing(SupplierLedger::getId);
    }

    private Purchase purchase(Long id, String date, String amount) {
        Purchase purchase = new Purchase();
        purchase.setId(id);
        purchase.setEntryDate(LocalDate.parse(date));
        purchase.setTotalAmount(new BigDecimal(amount));
        purchase.setSupplier(supplier);
        return purchase;
    }

    private SupplierPaymentHist payment(Long id, String date, String amount) {
        SupplierPaymentHist paid = new SupplierPaymentHist();
        paid.setId(id);
        paid.setDateOfTransaction(LocalDate.parse(date));
        paid.setPaidAmount(new BigDecimal(amount));
        paid.setSupplier(supplier);
        return paid;
    }

    @Test
    @DisplayName("A purchase raises what we owe; a payment lowers it")
    void theBalanceRunsTheOtherWayToACustomer() {
        service.recordPurchase(purchase(1L, "2026-08-01", "243000"));
        assertEquals(new BigDecimal("243000.00"), service.currentPayable(7L));

        service.recordPayment(payment(1L, "2026-08-05", "100000"));
        assertEquals(new BigDecimal("143000.00"), service.currentPayable(7L));

        // The credit is the purchase, not the payment. Backwards, this would report the
        // business as being owed by the people it owes.
        SupplierLedger bought = table.get(0);
        assertEquals(new BigDecimal("243000.00"), bought.getCreditAmount());
        assertEquals(BigDecimal.ZERO, bought.getDebitAmount());

        SupplierLedger paid = table.get(1);
        assertEquals(new BigDecimal("100000.00"), paid.getDebitAmount());
        assertEquals(BigDecimal.ZERO, paid.getCreditAmount());
    }

    @Test
    @DisplayName("Purchases accumulate - the bug this ledger exists to fix")
    void purchasesAccumulate() {
        // The five Akbar Poultry purchases, in order. The old code left 1,95,000 on record
        // after these - the last purchase's total - instead of the running total.
        service.recordPurchase(purchase(1L, "2024-08-08", "157500"));
        service.recordPurchase(purchase(2L, "2024-08-09", "72000"));
        service.recordPurchase(purchase(4L, "2025-05-10", "50000"));
        service.recordPurchase(purchase(8L, "2026-01-07", "338000"));
        service.recordPurchase(purchase(9L, "2026-03-25", "195000"));

        assertEquals(new BigDecimal("812500.00"), service.currentPayable(7L));
        assertEquals(new BigDecimal("812500.00"), supplier.getPendingPayment(),
                "the stored column must follow the ledger, not replace it");
    }

    @Test
    @DisplayName("The same purchase cannot be billed twice")
    void postingIsIdempotent() {
        // A retry after a half-failed save must not double the payable, which is the one
        // mistake a payables ledger must never make.
        service.recordPurchase(purchase(1L, "2026-08-01", "243000"));
        service.recordPurchase(purchase(1L, "2026-08-01", "243000"));

        assertEquals(1, table.size());
        assertEquals(new BigDecimal("243000.00"), service.currentPayable(7L));
    }

    @Test
    @DisplayName("A payment cannot be recorded twice either")
    void paymentsAreIdempotentToo() {
        service.recordPurchase(purchase(1L, "2026-08-01", "243000"));
        service.recordPayment(payment(1L, "2026-08-05", "50000"));
        service.recordPayment(payment(1L, "2026-08-05", "50000"));

        assertEquals(2, table.size());
        assertEquals(new BigDecimal("193000.00"), service.currentPayable(7L));
    }

    @Test
    @DisplayName("On a shared date the purchase sorts above the payment that settles it")
    void purchaseSortsBeforePaymentOnOneDate() {
        service.recordPurchase(purchase(2L, "2024-08-09", "72000"));
        service.recordPayment(payment(2L, "2024-08-09", "50000"));

        List<SupplierLedger> ordered = table.stream().sorted(statementOrder()).toList();
        assertEquals(TransactionType.PURCHASE, ordered.get(0).getTransactionType());
        assertEquals(TransactionType.PAYMENT, ordered.get(1).getTransactionType());
        // A payment printed above the purchase reads as money paid against nothing.
        assertEquals(new BigDecimal("72000.00"), ordered.get(0).getRunningBalance());
        assertEquals(new BigDecimal("22000.00"), ordered.get(1).getRunningBalance());
    }

    @Test
    @DisplayName("A backdated purchase rewrites every balance after it")
    void backdatingRecalculates() {
        service.recordPurchase(purchase(1L, "2026-08-10", "100000"));
        service.recordPurchase(purchase(2L, "2026-08-20", "50000"));
        assertEquals(new BigDecimal("150000.00"), service.currentPayable(7L));

        // A load from the 5th, entered now. Without the recalculation the two later rows keep
        // balances that no longer follow from the rows above them, and the statement's closing
        // figure disagrees with its own lines.
        service.recordPurchase(purchase(3L, "2026-08-05", "25000"));

        List<SupplierLedger> ordered = table.stream().sorted(statementOrder()).toList();
        assertEquals(new BigDecimal("25000.00"), ordered.get(0).getRunningBalance());
        assertEquals(new BigDecimal("125000.00"), ordered.get(1).getRunningBalance());
        assertEquals(new BigDecimal("175000.00"), ordered.get(2).getRunningBalance());
        assertEquals(new BigDecimal("175000.00"), service.currentPayable(7L));
    }

    @Test
    @DisplayName("A backdated row is flagged, so the rewrite is explained")
    void backdatedRowsAreMarked() {
        service.recordPurchase(purchase(1L, "2026-08-20", "50000"));
        service.recordPurchase(purchase(2L, "2026-08-05", "25000"));

        assertFalse(table.get(0).isBackdated(), "the first row has nothing after it");
        assertTrue(table.get(1).isBackdated(),
                "without this flag the recalculation of earlier balances looks like tampering");
    }

    @Test
    @DisplayName("What we owe now comes from the newest row by date, not the highest id")
    void currentPayableReadsStatementOrder() {
        service.recordPurchase(purchase(1L, "2026-08-20", "50000"));
        // Entered second, dated earlier: the highest id is now the oldest transaction, so
        // reading by id alone would report a balance from the middle of the account.
        service.recordPurchase(purchase(2L, "2026-08-05", "25000"));

        assertEquals(new BigDecimal("75000.00"), service.currentPayable(7L));
    }

    @Test
    @DisplayName("A purchase with no supplier is refused rather than posted to nobody")
    void refusesAPurchaseWithNoSupplier() {
        Purchase orphan = purchase(1L, "2026-08-01", "1000");
        orphan.setSupplier(null);

        assertThrows(IllegalStateException.class, () -> service.recordPurchase(orphan));
        assertTrue(table.isEmpty());
    }

    @Test
    @DisplayName("A purchase of zero still posts, so the load is on the account")
    void zeroAmountStillPosts() {
        // Purchase 7 is exactly this: 960 birds recorded for nothing. Leaving it off the
        // ledger would hide the data problem; posting it at zero leaves it visible.
        service.recordPurchase(purchase(7L, "2025-10-04", "0"));

        assertEquals(1, table.size());
        assertEquals(BigDecimal.ZERO.setScale(2), service.currentPayable(7L));
    }

    @Test
    @DisplayName("An empty account owes nothing rather than failing")
    void emptyAccount() {
        assertEquals(BigDecimal.ZERO, service.currentPayable(7L));
    }
}
