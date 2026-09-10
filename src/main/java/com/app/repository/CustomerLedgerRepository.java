package com.app.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.entity.Customer;
import com.app.entity.CustomerLedger;

@Repository
public interface CustomerLedgerRepository extends JpaRepository<CustomerLedger, Long> {
    
    // Find all ledger entries for a customer
    List<CustomerLedger> findByCustomerOrderByTransactionDateAsc(Customer customer);
    
    // Find ledger entries for a customer within date range
    List<CustomerLedger> findByCustomerAndTransactionDateBetweenOrderByTransactionDateAsc(
            Customer customer, LocalDate startDate, LocalDate endDate);
    
    // Find all ledger entries from a specific date onwards (for backdate recalculation)
    List<CustomerLedger> findByCustomerAndTransactionDateGreaterThanEqualOrderByTransactionDateAsc(
            Customer customer, LocalDate fromDate);
    
    // Find ledger entries by reference
    List<CustomerLedger> findByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
    
    // Get latest ledger entry for a customer (to get current running balance)
    @Query("SELECT cl FROM CustomerLedger cl WHERE cl.customer = :customer " +
           "ORDER BY cl.transactionDate DESC, cl.id DESC")
    List<CustomerLedger> findLatestByCustomer(@Param("customer") Customer customer);
    
    // Delete all ledger entries for a specific reference (for migration/cleanup)
    void deleteByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
    
    // Find all transactions on or after a date (for global recalculation)
    @Query("SELECT cl FROM CustomerLedger cl WHERE cl.transactionDate >= :fromDate " +
           "ORDER BY cl.customer.id, cl.transactionDate ASC, cl.id ASC")
    List<CustomerLedger> findAllFromDate(@Param("fromDate") LocalDate fromDate);

    /**
     * Statement rows in date order, with the id breaking ties so several
     * transactions on the same day read in the order they were entered. The
     * derived finders above order by date alone, which leaves same-day rows in
     * whatever order the database returns them - visible on a statement as a
     * running balance that appears to jump backwards.
     */
    @Query("SELECT cl FROM CustomerLedger cl WHERE cl.customer = :customer " +
           "AND cl.transactionDate BETWEEN :startDate AND :endDate " +
           "ORDER BY cl.transactionDate ASC, cl.id ASC")
    List<CustomerLedger> findForStatement(@Param("customer") Customer customer,
                                         @Param("startDate") LocalDate startDate,
                                         @Param("endDate") LocalDate endDate);

    @Query("SELECT cl FROM CustomerLedger cl WHERE cl.customer = :customer " +
           "ORDER BY cl.transactionDate ASC, cl.id ASC")
    List<CustomerLedger> findAllForStatement(@Param("customer") Customer customer);

    /**
     * Balance brought forward into a statement: the running balance on the last
     * row strictly before the given date, which already accounts for the whole
     * history behind it.
     */
    @Query("SELECT cl FROM CustomerLedger cl WHERE cl.customer = :customer " +
           "AND cl.transactionDate < :date " +
           "ORDER BY cl.transactionDate DESC, cl.id DESC")
    List<CustomerLedger> findLatestBefore(@Param("customer") Customer customer,
                                          @Param("date") LocalDate date,
                                          Limit limit);
}
