package com.app.controller;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.dashboard.DashboardOverviewDTO;
import com.app.entity.Dashboard;
import com.app.service.DashboardOverviewService;
import com.app.service.DashboardService;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

	private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);

	@Autowired
	private DashboardService dashboardService;

	@Autowired
	private DashboardOverviewService overviewService;

	/**
	 * Everything the dashboard shows, for the given date or today.
	 *
	 * Replaces four calls from the screen, three of which hit endpoints that had
	 * never existed - /route-wise, /high-balance-customers and /route-pending - so
	 * the dashboard could only ever show the seven today-only tiles below.
	 */
	@GetMapping("/overview")
	public ResponseEntity<DashboardOverviewDTO> getOverview(
			@RequestParam(required = false)
			@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {

		logger.info("Dashboard overview requested for {}", date == null ? "today" : date);
		return ResponseEntity.ok(overviewService.getOverview(date));
	}

	/**
	 * The original seven today-only figures, kept for anything still calling it.
	 * /overview carries these and much more, and honours the date it is given.
	 */
	@GetMapping("/data")
	public Dashboard getAllData() {
		logger.info("Fetching all Dashboard Data");
		return dashboardService.getDashboardData();
	}
}
