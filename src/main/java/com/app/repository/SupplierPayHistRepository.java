package com.app.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.entity.Purchase;
import com.app.entity.SupplierPaymentHist;

public interface SupplierPayHistRepository extends JpaRepository<SupplierPaymentHist, Long>{


	 //@Query("SELECT sph FROM SupplierPaymentHist sph WHERE sph.purchase = :purchase ORDER BY sph.id DESC")
	    Optional<SupplierPaymentHist> findTopByPurchaseOrderByIdDesc(Purchase purchase);

}
