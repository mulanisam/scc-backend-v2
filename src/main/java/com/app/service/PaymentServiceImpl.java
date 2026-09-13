package com.app.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.CustomerPaymentDTO;
import com.app.utility.MoneyRules;
import com.app.utility.SmsMessageBuilder;
import com.app.entity.Customer;
import com.app.entity.CustomerPayment;
import com.app.entity.MessageOutbox.Channel;
import com.app.entity.MessageOutbox.MessageType;
import com.app.repository.CustomerPaymentRepository;
import com.app.repository.CustomerRepository;

import com.app.exception.ResourceNotFoundException;

@Service
public class PaymentServiceImpl implements PaymentService {

    private static final Logger logger = LoggerFactory.getLogger(PaymentServiceImpl.class);

    @Autowired
    private CustomerPaymentRepository paymentRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private MessagingService messagingService;

    @Transactional
    @Override
    public CustomerPayment createPayment(CustomerPaymentDTO paymentDTO) {
        logger.debug("Creating payment of {} for customer {}", paymentDTO.getAmount(), paymentDTO.getCustomerId());
        
        try {
            // Validate customer
            Customer customer = customerRepository.findById(paymentDTO.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id: " + paymentDTO.getCustomerId()));
            
            // Validate payment amount
            if (paymentDTO.getAmount() == null || paymentDTO.getAmount().signum() <= 0) {
                throw new IllegalArgumentException("Payment amount must be greater than zero");
            }
            
            // Check if payment exceeds current balance
            BigDecimal currentBalance = ledgerService.getCurrentBalance(customer);
            if (paymentDTO.getAmount().compareTo(currentBalance) > 0) {
                logger.warn("Payment amount {} exceeds current balance {} for customer {}", 
                        paymentDTO.getAmount(), currentBalance, customer.getName());
                // Allow but log warning - business might want to accept advance payments
            }
            
            // Create payment entity
            CustomerPayment payment = new CustomerPayment();
            payment.setCustomer(customer);
            payment.setPaymentDate(paymentDTO.getPaymentDate());
            payment.setAmount(MoneyRules.money(paymentDTO.getAmount()));
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

            queueReceipt(savedPayment, paymentDTO.isSendSms(), paymentDTO.isSendWhatsapp());

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

    /**
     * Acknowledges the receipt to the customer, on whichever channels were asked for.
     *
     * Queued in this transaction, like the sale path: the receipt row and the message
     * commit together, so a payment cannot exist without its message having been queued
     * and a rolled-back payment cannot leave one behind.
     *
     * Each channel gets the message its own approved template takes, and they are not
     * the same message. WhatsApp's pay_received says what arrived - "आपल्याकडुन ₹X जमा
     * झाले" - while the approved DLT template for SMS reads "सध्याची शिल्लक", a balance.
     * Sending the amount received in a slot whose words say "balance" would put the
     * wrong figure behind the wrong sentence, so SMS carries the new balance instead.
     *
     * The payment id goes into the idempotency key: a customer settling twice in one
     * morning gets two receipts, where a per-day key would have taken the second for a
     * duplicate of the first and dropped it.
     */
    private void queueReceipt(CustomerPayment payment, boolean sendSms, boolean sendWhatsapp) {
        if (!sendSms && !sendWhatsapp) {
            return;
        }

        try {
            Customer customer = payment.getCustomer();
            LocalDate date = payment.getPaymentDate();

            if (sendSms) {
                // The balance after this receipt, which is what the DLT wording states.
                SmsMessageBuilder.Message sms = SmsMessageBuilder.dailyBalance(
                        customer.getName(), date, ledgerService.getCurrentBalance(customer));
                messagingService.enqueue(customer, Channel.SMS, MessageType.PAYMENT_RECEIPT,
                        date, null, sms.variables(), sms.body(), payment.getId());
            }

            if (sendWhatsapp) {
                SmsMessageBuilder.Message whatsapp = SmsMessageBuilder.paymentReceived(
                        customer.getName(), date, payment.getAmount());
                messagingService.enqueue(customer, Channel.WHATSAPP, MessageType.PAYMENT_RECEIPT,
                        date, null, whatsapp.variables(), whatsapp.body(), payment.getId());
            }
        } catch (RuntimeException e) {
            // A receipt that cannot be queued must never fail the payment - the money
            // has been taken. The outbox is the record, so a gap in it is visible after.
            logger.error("Could not queue the receipt for payment {}: {}",
                    payment.getId(), e.getMessage());
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

    /**
     * Cancels a payment.
     *
     * The soft delete alone was not enough: it flagged the payment row and then
     * called recalculateBalancesFromDate, which re-chains the ledger rows that
     * exist - including the cancelled payment's credit. The debt stayed paid off
     * and the statement still showed the receipt. Nothing had reached production
     * because no payment has ever been recorded, but the path was wrong.
     *
     * A reversing entry is posted instead of deleting the credit, following the
     * same rule already applied to trip corrections: the original stays and the
     * correction sits beside it, so a statement shows what happened rather than
     * quietly losing a receipt the customer was given.
     */
    @Transactional
    @Override
    public void deletePayment(Long paymentId) {
        logger.info("Cancelling payment with ID: {}", paymentId);

        CustomerPayment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found with id: " + paymentId));

        if (payment.isDeleted()) {
            throw new IllegalStateException("Payment " + paymentId + " is already cancelled.");
        }

        payment.setDeleted(true);
        paymentRepository.save(payment);

        // Puts the debt back, and recalculates from the payment's own date so the
        // reversal lands in the right place in the customer's history.
        ledgerService.reversePaymentLedgerEntry(payment);

        logger.info("Payment {} cancelled and reversed in the ledger", paymentId);
    }
}
