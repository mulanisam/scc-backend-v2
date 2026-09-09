package com.app.entity;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerLedger {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;
    
    @Column(nullable = false)
    private LocalDate transactionDate;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TransactionType transactionType;
    
    private String referenceType; // SALE, PAYMENT, OPENING_BALANCE
    
    private Long referenceId; // Sale ID or Payment ID
    
    @Column(nullable = false)
    private Double debitAmount; // Sale amount (increases balance)
    
    @Column(nullable = false)
    private Double creditAmount; // Payment (decreases balance)
    
    @Column(nullable = false)
    private Double runningBalance; // Balance after this transaction
    
    @Column(length = 500)
    private String description;
    
    private String paymentMode; // For payment transactions
    
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    
    @Column(nullable = false)
    private boolean isBackdated = false;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }
    
    public enum TransactionType {
        OPENING_BALANCE,
        SALE,
        PAYMENT,
        CREDIT_NOTE,
        DEBIT_NOTE,
        ADJUSTMENT
    }
}
