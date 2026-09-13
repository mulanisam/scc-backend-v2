package com.app.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.app.dto.CustomerLedgerDTO;
import com.app.dto.CustomerStatementDTO;
import com.app.entity.Customer;
import com.app.entity.CustomerLedger;
import com.app.entity.Sale;
import com.app.entity.TradingEntry;
import com.app.entity.CustomerPayment;

public interface LedgerService {
    
    /**
     * Create ledger entry for a sale
     */
    CustomerLedger createSaleLedgerEntry(Sale sale);

    /**
     * Posts a wholesale trading entry to the party's ledger account.
     *
     * The same debit-and-credit as a sale, and deliberately the same TransactionType, so
     * one statement reads the retail and wholesale history of an account without a reader
     * having to know which subsystem recorded a line. What differs is reference_type -
     * TRADING_ENTRY - which is how the statement knows where to find the birds and weight.
     *
     * Trading entries did not post to the ledger at all before. That was survivable while
     * the table was empty; it stopped being survivable when route 9's 44 lakh moved here,
     * because a new entry would have left the balance where it was.
     */
    CustomerLedger createTradingLedgerEntry(TradingEntry entry);
    
    /**
     * Create ledger entry for a payment
     */
    CustomerLedger createPaymentLedgerEntry(CustomerPayment payment);

    /**
     * Reverse a cancelled payment by posting a debit against it, which puts the
     * debt back on the account and leaves both entries visible on the statement.
     */
    CustomerLedger reversePaymentLedgerEntry(CustomerPayment payment);

    /**
     * Create opening balance ledger entry
     */
    CustomerLedger createOpeningBalanceEntry(Customer customer, BigDecimal openingBalance, LocalDate asOfDate);
    
    /**
     * Get customer ledger entries with optional date range
     */
    List<CustomerLedgerDTO> getCustomerLedger(Long customerId, LocalDate startDate, LocalDate endDate);
    
    /**
     * Assemble a full account statement: identity, period, balance brought
     * forward, the transactions with their sale detail resolved, and the closing
     * figures. getCustomerLedger returns only the rows in range, which is not
     * enough to present a statement that stands on its own.
     */
    CustomerStatementDTO getCustomerStatement(Long customerId, LocalDate startDate, LocalDate endDate);
    
    /**
     * Get current balance for a customer from ledger
     */
    BigDecimal getCurrentBalance(Customer customer);
    
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
    boolean isCreditLimitExceeded(Customer customer, BigDecimal additionalAmount);
    
    /**
     * Update customer balance amount field (sync with ledger)
     */
    void updateCustomerBalance(Customer customer);
}
