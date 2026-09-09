package com.app.service;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.CustomerPaymentDTO;
import com.app.entity.Customer;
import com.app.entity.CustomerPayment;
import com.app.repository.CustomerPaymentRepository;
import com.app.repository.CustomerRepository;

import cutsomException.ResourceNotFoundException;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentServiceImpl.class);

    @Autowired
    private CustomerPaymentRepository paymentRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private LedgerService ledgerService;

    @Transactional
    @Override
    public CustomerPayment createPayment(CustomerPaymentDTO paymentDTO) {
        logger.info("Creating payment entry: {}", paymentDTO);
        
        try {
            // Validate customer
            Customer customer = customerRepository.findById(paymentDTO.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id: " + paymentDTO.getCustomerId()));
            
            // Validate payment amount
            if (paymentDTO.getAmount() == null || paymentDTO.getAmount() <= 0) {
                throw new IllegalArgumentException("Payment amount must be greater than zero");
            }
            
            // Check if payment exceeds current balance
            Double currentBalance = ledgerService.getCurrentBalance(customer);
            if (paymentDTO.getAmount() > currentBalance) {
                logger.warn("Payment amount {} exceeds current balance {} for customer {}", 
                        paymentDTO.getAmount(), currentBalance, customer.getName());
                // Allow but log warning - business might want to accept advance payments
            }
            
            // Create payment entity
            CustomerPayment payment = new CustomerPayment();
            payment.setCustomer(customer);
            payment.setPaymentDate(paymentDTO.getPaymentDate());
            payment.setAmount(paymentDTO.getAmount());
            payment.setPaymentMode(paymentDTO.getPaymentMode());
            payment.setTransactionReference(paymentDTO.getTransactionReference());
            payment.setRemarks(paymentDTO.getRemarks());
            payment.setReceivedBy(paymentDTO.getReceivedBy());
            payment.setDeleted(false);
            
            // Save payment
            CustomerPayment savedPayment = paymentRepository.save(payment);
            logger.info("Payment saved with ID: {}", savedPayment.getId());
            
            // Create ledger entry (this handles backdate recalculation)
            ledgerService.createPaymentLedgerEntry(savedPayment);
            logger.info("Ledger entry created for payment ID: {}", savedPayment.getId());
            
            return savedPayment;
            
        } catch (ResourceNotFoundException e) {
            logger.error("Resource not found: {}", e.getMessage());
            throw e;
        } catch (IllegalArgumentException e) {
            logger.error("Invalid argument: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error creating payment: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create payment: " + e.getMessage());
        }
    }

    @Override
    public List<CustomerPayment> getCustomerPayments(Long customerId) {
        logger.info("Fetching payments for customer ID: {}", customerId);
        
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id: " + customerId));
        
        return paymentRepository.findByCustomerAndIsDeletedFalseOrderByPaymentDateDesc(customer);
    }

    @Override
    public List<CustomerPayment> getPaymentsByDateRange(LocalDate startDate, LocalDate endDate) {
        logger.info("Fetching payments from {} to {}", startDate, endDate);
        return paymentRepository.findByPaymentDateBetweenAndIsDeletedFalseOrderByPaymentDateDesc(startDate, endDate);
    }

    @Override
    public CustomerPayment getPaymentById(Long paymentId) {
        logger.info("Fetching payment with ID: {}", paymentId);
        return paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
    }

    @Transactional
    @Override
    public void deletePayment(Long paymentId) {
        logger.info("Deleting payment with ID: {}", paymentId);
        
        CustomerPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));
        
        // Soft delete
        payment.setDeleted(true);
        paymentRepository.save(payment);
        
        // Recalculate balances from the payment date
        ledgerService.recalculateBalancesFromDate(payment.getCustomer(), payment.getPaymentDate());
        
        logger.info("Payment {} soft deleted and balances recalculated", paymentId);
    }
}
