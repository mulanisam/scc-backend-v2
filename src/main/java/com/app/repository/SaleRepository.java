package com.app.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.app.entity.Sale;

public interface SaleRepository extends JpaRepository<Sale, Long>{

	    //All customer
	    @Query(value = "SELECT \r\n"
	    		+ "    c.route_id AS ROUTE,\r\n"
	    		+ "    c.name AS CITY,\r\n"
	    		+ "    cust.name AS 'CUSTOMER NAME',\r\n"
	    		+ "    cust.shop_name AS 'SHOP NAME',\r\n"
	    		+ "    cust.balance_amount AS 'BALANCE AMOUNT'\r\n"
	    		+ "FROM\r\n"
	    		+ "    customer cust\r\n"
	    		+ "        INNER JOIN\r\n"
	    		+ "    city c ON cust.city_id = c.id\r\n"
	    		+ "ORDER BY route_id ASC;",
                nativeQuery = true)
	    List<Map<String, Object>> findSaleReportByDateRange(@Param("startDate") LocalDate startDate, 
	                                                         @Param("endDate") LocalDate endDate);
        //by customer Id
	    @Query(value = "SELECT s.route_id AS ROUTE,c1.name AS CITY, v.vehicle_no AS VEHICLE, d.name AS DRIVER, " +
                "c.name AS 'CUSTOMER NAME', c.shop_name AS 'SHOP NAME', s.date AS 'SALE DATE', " +
                "s.birds AS BIRDS, s.kilograms AS 'WEIGHT', s.rate AS RATE, s.amount AS 'AMOUNT', " +
                "s.payment AS 'PAYMENT RECEIVED', s.pending AS 'PAYMENT PENDING', " +
                "s.balance_pending AS 'BALANCE PENDING', s.description AS 'DESCRIPTION' " +
                "FROM poultry_db_test.sale s " +
                "INNER JOIN vehicle v ON s.vehicle_no = v.id " +
                "INNER JOIN driver d ON s.driver_id = d.id " +
                "INNER JOIN customer c ON s.customer_id = c.id " +
                "INNER JOIN city c1 ON c.city_id = c1.id " +
                "WHERE c.id=:customerId AND  s.date BETWEEN :startDate AND :endDate ORDER BY s.route_id ASC", 
        nativeQuery = true)
		List<Map<String, Object>> findSaleReportByIdAndDateRange(@Param("customerId") Long customerId, @Param("startDate") LocalDate startDate, 
                @Param("endDate") LocalDate endDate);

	    
	    //By Route
	    @Query(value = "SELECT s.route_id AS ROUTE,c1.name AS CITY, v.vehicle_no AS VEHICLE, d.name AS DRIVER, " +
                "c.name AS 'CUSTOMER NAME', c.shop_name AS 'SHOP NAME', s.date AS 'SALE DATE', " +
                "s.birds AS BIRDS, s.kilograms AS 'WEIGHT', s.rate AS RATE, s.amount AS 'AMOUNT', " +
                "s.payment AS 'PAYMENT RECEIVED', s.pending AS 'PAYMENT PENDING', " +
                "c.balance_amount AS 'TOTAL BALANCE', s.description AS 'DESCRIPTION' " +
                "FROM poultry_db_test.sale s " +
                "INNER JOIN vehicle v ON s.vehicle_no = v.id " +
                "INNER JOIN driver d ON s.driver_id = d.id " +
                "INNER JOIN customer c ON s.customer_id = c.id " +
                "INNER JOIN city c1 ON c.city_id = c1.id " +
                "WHERE s.route_id=:routeId AND  s.date BETWEEN :startDate AND :endDate", 
        nativeQuery = true)
		List<Map<String, Object>> findByRouteAndDateBetween(@Param("routeId") Long customerId, @Param("startDate") LocalDate startDate, 
                @Param("endDate") LocalDate endDate);

	  //All Route
	    @Query(value = "SELECT s.route_id AS ROUTE,c1.name AS CITY, v.vehicle_no AS VEHICLE, d.name AS DRIVER, " +
                "c.name AS 'CUSTOMER NAME', c.shop_name AS 'SHOP NAME', s.date AS 'SALE DATE', " +
                "s.birds AS BIRDS, s.kilograms AS 'WEIGHT', s.rate AS RATE, s.amount AS 'AMOUNT', " +
                "s.payment AS 'PAYMENT RECEIVED', s.pending AS 'PAYMENT PENDING', " +
                "c.balance_amount AS 'TOTAL BALANCE', s.description AS 'DESCRIPTION' " +
                "FROM poultry_db_test.sale s " +
                "INNER JOIN vehicle v ON s.vehicle_no = v.id " +
                "INNER JOIN driver d ON s.driver_id = d.id " +
                "INNER JOIN customer c ON s.customer_id = c.id " +
                "INNER JOIN city c1 ON c.city_id = c1.id " +
                "WHERE s.date BETWEEN :startDate AND :endDate ", 
        nativeQuery = true)
		List<Map<String, Object>> findByAllRouteAndDateBetween(@Param("startDate") LocalDate startDate, 
	                                                         @Param("endDate") LocalDate endDate);
	
		Sale findTopByCustomerIdOrderByIdDesc(Long id);
	    
		// Find all sales for a customer ordered by date (for migration)
		List<Sale> findByCustomerOrderByDateAsc(com.app.entity.Customer customer);
	    
	    
	    
	    
	}


