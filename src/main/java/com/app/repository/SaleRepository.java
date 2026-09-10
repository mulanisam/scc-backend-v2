package com.app.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.app.entity.Sale;

public interface SaleRepository extends JpaRepository<Sale, Long>{

		Sale findTopByCustomerIdOrderByIdDesc(Long id);
	    
		// Find all sales for a customer ordered by date (for migration)
		List<Sale> findByCustomerOrderByDateAsc(com.app.entity.Customer customer);
	    
	    
	    
	    
	}


