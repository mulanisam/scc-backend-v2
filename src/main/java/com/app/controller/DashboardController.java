package com.app.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.app.entity.Dashboard;
import com.app.service.DashboardService;

@RestController
@RequestMapping("/dashboard")
public class DashboardController {

	private static final Logger logger = LoggerFactory.getLogger(DashboardController.class);
	
	@Autowired
    private DashboardService dashboardService;
	
	 @GetMapping("/data")
	    public Dashboard getAllData() {
	        logger.info("Fetching all Dashboard Data");
	        return dashboardService.getDashboardData();
	    }
}
