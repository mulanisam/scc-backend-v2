package com.app.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.app.entity.Customer;
import com.app.entity.CustomerPayment;

@Repository
public interface CustomerPaymentRepository extends JpaRepository<CustomerPayment, Long> {
    
    // Find all payments for a customer
    List<CustomerPayment> findByCustomerAndIsDeletedFalseOrderByPaymentDateDesc(Customer customer);
    
    // Find payments within date range
    List<CustomerPayment> findByPaymentDateBetweenAndIsDeletedFalseOrderByPaymentDateDesc(
            LocalDate startDate, LocalDate endDate);
    
    // Find payments by customer and date range
    List<CustomerPayment> findByCustomerAndPaymentDateBetweenAndIsDeletedFalseOrderByPaymentDateDesc(
            Customer customer, LocalDate startDate, LocalDate endDate);
    
    // Find all payments on a specific date
    List<CustomerPayment> findByPaymentDateAndIsDeletedFalse(LocalDate paymentDate);
}
