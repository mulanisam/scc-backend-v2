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

	/**
	 * Everyone who can be reached on this number, on either of their two fields.
	 *
	 * Both columns, because a number held as somebody else's fallback still rings
	 * their phone: a statement sent to it would carry one shop's balance to another.
	 * Checking only mobile_no would let the second column quietly rebuild the shared
	 * -number problem the contact screen exists to clear.
	 */
	@Query("SELECT c FROM Customer c WHERE c.mobileNo = :mobileNo OR c.alternateMobileNo = :mobileNo")
	List<Customer> findByEitherMobileNo(@Param("mobileNo") String mobileNo);

	/**
	 * How many customers may be messaged on WhatsApp. Currently zero, which is the
	 * point of showing it: the queue fills and every row skips until somebody opts
	 * customers in, and a dashboard that did not say so would look broken instead.
	 */
	@Query("SELECT COUNT(c) FROM Customer c WHERE c.whatsappOptInAt IS NOT NULL AND c.whatsappOptOut = false")
	long countWhatsappOptedIn();

}

