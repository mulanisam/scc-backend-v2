package com.app.repository;

import java.util.List;
import java.util.Optional;

import java.math.BigDecimal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.entity.Customer;

public interface CustomerRepository extends JpaRepository<Customer, Long> {
	
	@Query("SELECT cust FROM Customer cust " +
		       "JOIN cust.city c " +
		       "WHERE c.route.id = :routeId")
	Optional<List<Customer>> findByRouteId(@Param("routeId") Long routId);
	
	@Modifying
    @Query("UPDATE Customer c SET c.balanceAmount = c.balanceAmount + :amount WHERE c.id = :customerId")
    int updateBalanceAmount(@Param("customerId") Long customerId, @Param("amount") BigDecimal amount);

	/**
	 * Everyone recorded against one number. Returns a list rather than an Optional
	 * because sharing is the situation being detected, not an error to be hidden:
	 * production has 11 numbers on two or more customers, one on seven.
	 */
	List<Customer> findByMobileNo(String mobileNo);

}

