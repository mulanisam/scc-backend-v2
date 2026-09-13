package com.app.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.entity.SupplierLedger;

@Repository
public interface SupplierLedgerRepository extends JpaRepository<SupplierLedger, Long> {

    /**
     * One supplier's account in statement order.
     *
     * Purchases sort before payments on a shared date, because that is the order the events
     * happened in: a payment printed above the purchase it settles reads as money paid
     * against nothing.
     */
    @Query("""
            SELECT l FROM SupplierLedger l
             WHERE l.supplier.id = :supplierId
               AND (:from IS NULL OR l.transactionDate >= :from)
               AND (:to IS NULL OR l.transactionDate <= :to)
             ORDER BY l.transactionDate ASC,
                      CASE l.transactionType
                           WHEN com.app.entity.SupplierLedger$TransactionType.PURCHASE THEN 0
                           ELSE 1 END ASC,
                      l.id ASC
            """)
    List<SupplierLedger> findForSupplier(@Param("supplierId") Long supplierId,
                                        @Param("from") LocalDate from,
                                        @Param("to") LocalDate to);

    /**
     * The newest row, whose running balance is what we owe now.
     *
     * Ordered the same way as the statement rather than by id alone: a backdated purchase
     * entered today has the highest id and the earliest date, so ordering by id would report
     * a balance from the middle of the account as the current one.
     */
    @Query("""
            SELECT l FROM SupplierLedger l
             WHERE l.supplier.id = :supplierId
             ORDER BY l.transactionDate DESC,
                      CASE l.transactionType
                           WHEN com.app.entity.SupplierLedger$TransactionType.PURCHASE THEN 0
                           ELSE 1 END DESC,
                      l.id DESC
            """)
    List<SupplierLedger> findLatest(@Param("supplierId") Long supplierId, Limit limit);

    /**
     * Everything from a date onwards, oldest first, for recomputing after a backdated entry.
     *
     * A purchase entered for last week makes every balance after it wrong, so they are read
     * back in order and rewritten - the same thing LedgerServiceImpl does on the sales side.
     */
    @Query("""
            SELECT l FROM SupplierLedger l
             WHERE l.supplier.id = :supplierId
               AND l.transactionDate >= :from
             ORDER BY l.transactionDate ASC,
                      CASE l.transactionType
                           WHEN com.app.entity.SupplierLedger$TransactionType.PURCHASE THEN 0
                           ELSE 1 END ASC,
                      l.id ASC
            """)
    List<SupplierLedger> findFromDate(@Param("supplierId") Long supplierId,
                                      @Param("from") LocalDate from);

    /** The balance immediately before a date, for an opening figure on a statement. */
    @Query("""
            SELECT COALESCE(SUM(l.creditAmount - l.debitAmount), 0)
              FROM SupplierLedger l
             WHERE l.supplier.id = :supplierId
               AND l.transactionDate < :before
            """)
    BigDecimal balanceBefore(@Param("supplierId") Long supplierId,
                             @Param("before") LocalDate before);

    /**
     * What we owe every supplier, in one query.
     *
     * Summed from the rows rather than read off supplier.pending_payment, which is what the
     * old code did and got wrong. The column is still maintained as a convenience, but this
     * is the figure the payables screen shows - so a disagreement between the two shows up as
     * a disagreement rather than being hidden behind whichever one was read.
     */
    @Query("""
            SELECT l.supplier.id, SUM(l.creditAmount), SUM(l.debitAmount),
                   SUM(l.creditAmount - l.debitAmount), MAX(l.transactionDate), COUNT(l)
              FROM SupplierLedger l
             GROUP BY l.supplier.id
            """)
    List<Object[]> summariseBySupplier();

    boolean existsByReferenceTypeAndReferenceId(String referenceType, Long referenceId);
}
