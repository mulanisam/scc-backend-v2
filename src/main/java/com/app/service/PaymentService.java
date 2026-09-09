package com.app.service;

import java.time.LocalDate;
import java.util.List;

import com.app.dto.CustomerPaymentDTO;
import com.app.entity.CustomerPayment;

public interface PaymentService {
    
    /**
     * Create a new payment entry
     */
    CustomerPayment createPayment(CustomerPaymentDTO paymentDTO);
    
    /**
     * Get all payments for a customer
     */
    List<CustomerPayment> getCustomerPayments(Long customerId);
    
    /**
     * Get payments within date range
     */
    List<CustomerPayment> getPaymentsByDateRange(LocalDate startDate, LocalDate endDate);
    
    /**
     * Get payment by ID
     */
    CustomerPayment getPaymentById(Long paymentId);
    
    /**
     * Delete (soft delete) a payment
     */
    void deletePayment(Long paymentId);
}
