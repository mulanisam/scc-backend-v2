package com.app.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.CustomerLedgerDTO;
import com.app.entity.Customer;
import com.app.entity.CustomerLedger;
import com.app.entity.CustomerLedger.TransactionType;
import com.app.entity.CustomerPayment;
import com.app.entity.Sale;
import com.app.repository.CustomerLedgerRepository;
import com.app.repository.CustomerRepository;

@Service
public class LedgerServiceImpl implements LedgerService {

    private static final Logger logger = LoggerFactory.getLogger(LedgerServiceImpl.class);

    @Autowired
    private CustomerLedgerRepository ledgerRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Override
    @Transactional
    public CustomerLedger createSaleLedgerEntry(Sale sale) {
        logger.info("Creating ledger entry for sale ID: {}", sale.getId());
        
        Customer customer = sale.getCustomer();
        CustomerLedger ledger = new CustomerLedger();
        ledger.setCustomer(customer);
        ledger.setTransactionDate(sale.getDate());
        ledger.setTransactionType(TransactionType.SALE);
        ledger.setReferenceType("SALE");
        ledger.setReferenceId(sale.getId());
        
        // Debit = Sale amount (increases customer's debt)
        Double saleAmount = sale.getAmount() != null ? sale.getAmount().doubleValue() : 0.0;
        ledger.setDebitAmount(saleAmount);
        
        // Credit = Payment received (decreases debt)
        Double paymentAmount = sale.getPayment() != null ? sale.getPayment().doubleValue() : 0.0;
        ledger.setCreditAmount(paymentAmount);
        
        ledger.setPaymentMode(sale.getPaymentMode());
        ledger.setDescription("Sale - " + (sale.getBirds() != null ? sale.getBirds() + " birds, " : "") + 
                             (sale.getKilograms() != null ? sale.getKilograms() + " kg" : ""));
        
        // Check if this is a backdated entry
        LocalDate today = LocalDate.now();
        if (sale.getDate().isBefore(today)) {
            ledger.setBackdated(true);
        }
        
        // Calculate running balance
        Double previousBalance = getCurrentBalance(customer);
        Double runningBalance = previousBalance + saleAmount - paymentAmount;
        ledger.setRunningBalance(runningBalance);
        
        CustomerLedger savedLedger = ledgerRepository.save(ledger);
        
        // If backdated, recalculate all balances from this date
        if (ledger.isBackdated()) {
            logger.info("Backdated sale detected. Recalculating balances from date: {}", sale.getDate());
            recalculateBalancesFromDate(customer, sale.getDate());
        } else {
            // Update customer balance
            customer.setBalanceAmount(runningBalance);
            customerRepository.save(customer);
        }
        
        return savedLedger;
    }

    @Override
    @Transactional
    public CustomerLedger createPaymentLedgerEntry(CustomerPayment payment) {
        logger.info("Creating ledger entry for payment ID: {}", payment.getId());
        
        Customer customer = payment.getCustomer();
        CustomerLedger ledger = new CustomerLedger();
        ledger.setCustomer(customer);
        ledger.setTransactionDate(payment.getPaymentDate());
        ledger.setTransactionType(TransactionType.PAYMENT);
        ledger.setReferenceType("PAYMENT");
        ledger.setReferenceId(payment.getId());
        ledger.setDebitAmount(0.0);
        ledger.setCreditAmount(payment.getAmount());
        ledger.setPaymentMode(payment.getPaymentMode());
        ledger.setDescription("Payment received - " + payment.getPaymentMode() + 
                             (payment.getTransactionReference() != null ? " (" + payment.getTransactionReference() + ")" : ""));
        
        // Check if backdated
        LocalDate today = LocalDate.now();
        if (payment.getPaymentDate().isBefore(today)) {
            ledger.setBackdated(true);
        }
        
        // Calculate running balance
        Double previousBalance = getCurrentBalance(customer);
        Double runningBalance = previousBalance - payment.getAmount();
        ledger.setRunningBalance(runningBalance);
        
        CustomerLedger savedLedger = ledgerRepository.save(ledger);
        
        // If backdated, recalculate all balances from this date
        if (ledger.isBackdated()) {
            logger.info("Backdated payment detected. Recalculating balances from date: {}", payment.getPaymentDate());
            recalculateBalancesFromDate(customer, payment.getPaymentDate());
        } else {
            // Update customer balance
            customer.setBalanceAmount(runningBalance);
            customerRepository.save(customer);
        }
        
        return savedLedger;
    }

    @Override
    @Transactional
    public CustomerLedger createOpeningBalanceEntry(Customer customer, Double openingBalance, LocalDate asOfDate) {
        logger.info("Creating opening balance entry for customer: {}, balance: {}", customer.getId(), openingBalance);
        
        CustomerLedger ledger = new CustomerLedger();
        ledger.setCustomer(customer);
        ledger.setTransactionDate(asOfDate);
        ledger.setTransactionType(TransactionType.OPENING_BALANCE);
        ledger.setReferenceType("OPENING_BALANCE");
        ledger.setReferenceId(null);
        ledger.setDebitAmount(openingBalance > 0 ? openingBalance : 0.0);
        ledger.setCreditAmount(openingBalance < 0 ? Math.abs(openingBalance) : 0.0);
        ledger.setRunningBalance(openingBalance);
        ledger.setDescription("Opening Balance");
        ledger.setBackdated(false);
        
        return ledgerRepository.save(ledger);
    }

    @Override
    public List<CustomerLedgerDTO> getCustomerLedger(Long customerId, LocalDate startDate, LocalDate endDate) {
        logger.info("Fetching ledger for customer: {}, from: {}, to: {}", customerId, startDate, endDate);
        
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new RuntimeException("Customer not found"));
        
        List<CustomerLedger> ledgerEntries;
        
        if (startDate != null && endDate != null) {
            ledgerEntries = ledgerRepository.findByCustomerAndTransactionDateBetweenOrderByTransactionDateAsc(
                    customer, startDate, endDate);
        } else {
            ledgerEntries = ledgerRepository.findByCustomerOrderByTransactionDateAsc(customer);
        }
        
        return ledgerEntries.stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public Double getCurrentBalance(Customer customer) {
        List<CustomerLedger> latestEntry = ledgerRepository.findLatestByCustomer(customer);
        if (latestEntry.isEmpty()) {
            return 0.0;
        }
        return latestEntry.get(0).getRunningBalance();
    }

    @Override
    @Transactional
    public void recalculateBalancesFromDate(Customer customer, LocalDate fromDate) {
        logger.info("Recalculating balances for customer: {} from date: {}", customer.getId(), fromDate);
        
        // Get the balance just before fromDate
        List<CustomerLedger> entriesBeforeDate = ledgerRepository
                .findByCustomerAndTransactionDateBetweenOrderByTransactionDateAsc(
                        customer, LocalDate.of(1900, 1, 1), fromDate.minusDays(1));
        
        Double startingBalance = 0.0;
        if (!entriesBeforeDate.isEmpty()) {
            startingBalance = entriesBeforeDate.get(entriesBeforeDate.size() - 1).getRunningBalance();
        }
        
        // Get all entries from fromDate onwards
        List<CustomerLedger> entriesToRecalculate = ledgerRepository
                .findByCustomerAndTransactionDateGreaterThanEqualOrderByTransactionDateAsc(customer, fromDate);
        
        // Sort by date and then by ID to ensure consistent ordering
        entriesToRecalculate.sort(Comparator
                .comparing(CustomerLedger::getTransactionDate)
                .thenComparing(CustomerLedger::getId));
        
        Double runningBalance = startingBalance;
        
        // Recalculate running balance for each entry
        for (CustomerLedger entry : entriesToRecalculate) {
            runningBalance = runningBalance + entry.getDebitAmount() - entry.getCreditAmount();
            entry.setRunningBalance(runningBalance);
            entry.setUpdatedAt(LocalDateTime.now());
        }
        
        // Save all updated entries
        ledgerRepository.saveAll(entriesToRecalculate);
        
        // Update customer's current balance
        customer.setBalanceAmount(runningBalance);
        customerRepository.save(customer);
        
        logger.info("Balance recalculation complete. New balance: {}", runningBalance);
    }

    @Override
    @Transactional
    public void recalculateAllBalances(Customer customer) {
        logger.info("Recalculating ALL balances for customer: {}", customer.getId());
        
        List<CustomerLedger> allEntries = ledgerRepository.findByCustomerOrderByTransactionDateAsc(customer);
        
        // Sort by date and then by ID
        allEntries.sort(Comparator
                .comparing(CustomerLedger::getTransactionDate)
                .thenComparing(CustomerLedger::getId));
        
        Double runningBalance = 0.0;
        
        for (CustomerLedger entry : allEntries) {
            runningBalance = runningBalance + entry.getDebitAmount() - entry.getCreditAmount();
            entry.setRunningBalance(runningBalance);
            entry.setUpdatedAt(LocalDateTime.now());
        }
        
        ledgerRepository.saveAll(allEntries);
        
        // Update customer balance
        customer.setBalanceAmount(runningBalance);
        customerRepository.save(customer);
        
        logger.info("Complete balance recalculation done. Final balance: {}", runningBalance);
    }

    @Override
    public boolean isCreditLimitExceeded(Customer customer, Double additionalAmount) {
        if (!customer.isCreditLimitEnabled() || customer.getCreditLimit() == null) {
            return false; // No credit limit
        }
        
        Double currentBalance = getCurrentBalance(customer);
        Double newBalance = currentBalance + additionalAmount;
        
        return newBalance > customer.getCreditLimit();
    }

    @Override
    @Transactional
    public void updateCustomerBalance(Customer customer) {
        Double currentBalance = getCurrentBalance(customer);
        customer.setBalanceAmount(currentBalance);
        customerRepository.save(customer);
    }

    private CustomerLedgerDTO convertToDTO(CustomerLedger ledger) {
        CustomerLedgerDTO dto = new CustomerLedgerDTO();
        dto.setId(ledger.getId());
        dto.setCustomerId(ledger.getCustomer().getId());
        dto.setCustomerName(ledger.getCustomer().getName());
        dto.setTransactionDate(ledger.getTransactionDate());
        dto.setTransactionType(ledger.getTransactionType());
        dto.setReferenceType(ledger.getReferenceType());
        dto.setReferenceId(ledger.getReferenceId());
        dto.setDebitAmount(ledger.getDebitAmount());
        dto.setCreditAmount(ledger.getCreditAmount());
        dto.setRunningBalance(ledger.getRunningBalance());
        dto.setDescription(ledger.getDescription());
        dto.setPaymentMode(ledger.getPaymentMode());
        dto.setCreatedAt(ledger.getCreatedAt());
        dto.setBackdated(ledger.isBackdated());
        return dto;
    }
}
