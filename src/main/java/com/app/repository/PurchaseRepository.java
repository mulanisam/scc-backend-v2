package com.app.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.entity.Purchase;

public interface PurchaseRepository  extends JpaRepository<Purchase, Long>{

	Optional<Purchase> findBySupplierIdAndEntryDate(Long supplierId, LocalDate date);

	/**
	 * Every purchase from one supplier on one date.
	 *
	 * The list form exists because the Optional one above throws when a supplier has two
	 * purchases on a day, which two of these nine do - Komarla Agrovet on 2025-09-06 - and
	 * Spring Data's "query did not return a unique result" made paying that supplier
	 * impossible. The caller can now say which one it means.
	 */
	List<Purchase> findAllBySupplierIdAndEntryDate(Long supplierId, LocalDate date);

	/** Every purchase for one supplier, newest first, for the payables drill-down. */
	List<Purchase> findBySupplierIdOrderByEntryDateDescIdDesc(Long supplierId);

	/** Purchases in a period, for the reports. */
	List<Purchase> findByEntryDateBetweenOrderByEntryDateAscIdAsc(LocalDate from, LocalDate to);

	/*
	 * Kept for the payment path's history, but no longer how the payable is maintained.
	 *
	 * This incremented supplier.pending_payment directly, and createPurchase then overwrote
	 * the result with the new purchase's total - so the increment was thrown away and the
	 * balance was wrong by everything that came before. SupplierLedgerService owns the figure
	 * now and derives it from the ledger rows.
	 */
	@Modifying
    @Query("UPDATE Supplier s SET s.pendingPayment = s.pendingPayment + :amount WHERE s.id = :supplierId")
    int updatePendingAmount(@Param("supplierId") Long supplierId, @Param("amount") BigDecimal amount);

}
