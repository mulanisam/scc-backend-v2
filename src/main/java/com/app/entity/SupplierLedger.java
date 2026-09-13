package com.app.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One movement on a supplier's account, and the balance after it.
 *
 * The purchase side's answer to CustomerLedger, and it exists for the same reason: a balance
 * that is stored rather than derived drifts, and there is nothing to audit it against. What
 * it replaces was a single column, supplier.pending_payment, overwritten with each new
 * purchase's total - so the amount owed to Akbar Poultry read 1,95,000 against five
 * purchases worth 8,12,500, and no purchase could be shown as settled because nothing
 * recorded which payment paid for what.
 *
 * <p><b>The balance moves the opposite way to a customer's.</b> A customer owes us, so their
 * balance rises on a debit; we owe a supplier, so theirs rises on a credit:
 *
 * <pre>
 *   CustomerLedger:  running_balance = previous + debit  - credit
 *   SupplierLedger:  running_balance = previous + credit - debit
 * </pre>
 *
 * That is the ordinary reading of a liability account - buying from a supplier credits them,
 * paying them debits them. It is the one thing about this table that is not a mirror image of
 * the other, so it is worth knowing before writing a query against it.
 */
@Entity
@Table(name = "supplier_ledger")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SupplierLedger {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    /** The day the purchase or payment happened, not the day it was typed in. */
    @Column(nullable = false)
    private LocalDate transactionDate;

    /*
     * varchar rather than a native enum, the lesson MessageOutbox already paid for: Hibernate
     * 6 maps @Enumerated(STRING) onto a MySQL enum by default and ddl-auto=validate then
     * rejects the varchar the migration created. Adding a transaction type should not need an
     * ALTER TABLE in three environments.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 30)
    private TransactionType transactionType;

    /** PURCHASE or SUPPLIER_PAYMENT - what referenceId points into. */
    @Column(length = 40)
    private String referenceType;

    private Long referenceId;

    /** Reduces what we owe. A payment made to the supplier. */
    @Column(nullable = false)
    private BigDecimal debitAmount = BigDecimal.ZERO;

    /** Raises what we owe. A purchase received from the supplier. */
    @Column(nullable = false)
    private BigDecimal creditAmount = BigDecimal.ZERO;

    /** What we owed after this row. Derived, never typed in. */
    @Column(nullable = false)
    private BigDecimal runningBalance;

    @Column(length = 500)
    private String description;

    @Column(length = 40)
    private String paymentMode;

    /**
     * True when the row was inserted with a date earlier than rows already present.
     *
     * Recorded because it explains why later balances were rewritten: a backdated purchase
     * forces every row after it to be recomputed, and without this flag that recalculation
     * looks like tampering.
     */
    @Column(nullable = false)
    private boolean isBackdated = false;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    public enum TransactionType {
        /** What was owed before the ledger began. */
        OPENING_BALANCE,
        /** Birds received. Raises the payable. */
        PURCHASE,
        /** Money paid to the supplier. Reduces the payable. */
        PAYMENT,
        /** Reduces the payable without money moving - a rejected load, a rate correction. */
        DEBIT_NOTE,
        /** Raises the payable without a purchase - an agreed charge. */
        CREDIT_NOTE,
        ADJUSTMENT
    }
}
