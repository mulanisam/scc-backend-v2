package com.app.service;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.app.dto.DashboardSummaryDTO;
import com.app.entity.Dashboard;
import com.app.repository.SaleDetailsRepository;
@Service
public class DashboardServiceImpl implements DashboardService {

	@Autowired
    private SaleDetailsRepository saleDetailsRepository;
	@Override
	public Dashboard getDashboardData() {
		Dashboard dashboard = new Dashboard();
		 
		 LocalDate today = LocalDate.now();
		 DashboardSummaryDTO summary = saleDetailsRepository.getSaleDetailsSummaryByDate(today);
		 dashboard.setTodaysSaleAmount(summary.getTotalAmount());
		 dashboard.setReturnToFarmBirds(summary.getReturnToFarm());
		 dashboard.setTodaysBirdsSale(summary.getTotalBirdSale());
		 dashboard.setTodaysMortality(summary.getMortality());
		 dashboard.setTodaysPayment(summary.getTotalPaymentReceived());
		 dashboard.setTodaysSaleWeight(summary.getTotalKilogramSale());
		 dashboard.setTodaysPending(summary.getTotalPending());
		return dashboard;
	}

}
