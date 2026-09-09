package com.app.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.app.dto.DashboardSummaryDTO;
import com.app.entity.Driver;
import com.app.entity.Route;
import com.app.entity.SaleDetails;
import com.app.entity.Vehicle;

@Repository
public interface SaleDetailsRepository extends JpaRepository<SaleDetails, Long> {
	
    Optional<SaleDetails> findByDateAndRouteAndVehicleAndDriver(LocalDate date, Route route, Vehicle vehicle, Driver driver);
    @Query(
    	    value = "SELECT " +
    	            "SUM(total_amount) AS totalAmount, " +
    	            "SUM(total_bird_sale) AS totalBirdSale, " +
    	            "SUM(total_kilogram_sale) AS totalKilogramSale, " +
    	            "SUM(total_payment_received) AS totalPaymentReceived, " +
    	            "SUM(mortality) AS mortality, " +
    	            "SUM(return_to_farm) AS returnToFarm, " +
    	            "SUM(total_pending) AS totalPending " +
    	            "FROM sale_details WHERE date = :date",
    	    nativeQuery = true
    	)
    DashboardSummaryDTO getSaleDetailsSummaryByDate(@Param("date") LocalDate date);


}