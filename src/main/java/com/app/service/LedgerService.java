package com.app.service;

import java.time.LocalDate;
import java.util.List;

import com.app.dto.CustomerLedgerDTO;
import com.app.entity.Customer;
import com.app.entity.CustomerLedger;
import com.app.entity.Sale;
import com.app.entity.CustomerPayment;

public interface LedgerService {
    
    /**
     * Create ledger entry for a sale
     */
    CustomerLedger createSaleLedgerEntry(Sale sale);
    
    /**
     * Create ledger entry for a payment
     */
    CustomerLedger createPaymentLedgerEntry(CustomerPayment payment);
    
    /**
     * Create opening balance ledger entry
     */
    CustomerLedger createOpeningBalanceEntry(Customer customer, Double openingBalance, LocalDate asOfDate);
    
    /**
     * Get customer ledger entries with optional date range
     */
    List<CustomerLedgerDTO> getCustomerLedger(Long customerId, LocalDate startDate, LocalDate endDate);
    
    /**
     * Get current balance for a customer from ledger
     */
    Double getCurrentBalance(Customer customer);
    
    /**
     * Recalculate running balances from a specific date onwards (for backdate handling)
     * This is triggered when a backdated transaction is inserted
     */
    void recalculateBalancesFromDate(Customer customer, LocalDate fromDate);
    
    /**
     * Recalculate all balances for a customer (complete rebuild)
     */
    void recalculateAllBalances(Customer customer);
    
    /**
     * Check if credit limit is exceeded (if enabled)
     */
    boolean isCreditLimitExceeded(Customer customer, Double additionalAmount);
    
    /**
     * Update customer balance amount field (sync with ledger)
     */
    void updateCustomerBalance(Customer customer);
}
