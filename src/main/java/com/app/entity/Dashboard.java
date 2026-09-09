package com.app.entity;

import lombok.Data;

@Data
public class Dashboard {
	private Double todaysSaleAmount;
	private Double todaysPayment;
	private Double todaysPending;
	private Double todaysBirdsSale;
	private Double todaysSaleWeight;
	private Double todaysMortality;
	private Double returnToFarmBirds;
}
