package com.app.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class CustomerPayment extends AuditableEntity {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne
    @JoinColumn(name = "customer_id", nullable = false)
    private Customer customer;
    
    @Column(nullable = false)
    private LocalDate paymentDate;
    
    @Column(nullable = false)
    private BigDecimal amount;
    
    @Column(nullable = false)
    private String paymentMode; // CASH, UPI, CHEQUE, BANK_TRANSFER, etc.
    
    private String transactionReference; // Cheque number, UPI transaction ID, etc.
    
    @Column(length = 500)
    private String remarks;
    
    private String receivedBy; // User who recorded the payment

    @Column(nullable = false)
    private boolean isDeleted = false;

    // createdAt / updatedAt, and the @PrePersist and @PreUpdate callbacks that
    // maintained them, have moved to AuditableEntity so that every business
    // record is stamped the same way and also records WHO made the change.
}
