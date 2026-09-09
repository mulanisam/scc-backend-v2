package com.app.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.entity.Purchase;

public interface PurchaseRepository  extends JpaRepository<Purchase, Long>{

	Optional<Purchase> findBySupplierIdAndEntryDate(Long supplierId, String date);
	@Modifying
    @Query("UPDATE Supplier s SET s.pendingPayment = s.pendingPayment + :amount WHERE s.id = :supplierId")
    int updatePendingAmount(@Param("supplierId") Long supplierId, @Param("amount") Double amount);

}
