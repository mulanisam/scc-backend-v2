package com.app.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.app.entity.Customer;
import com.app.entity.Driver;
import com.app.entity.Route;
import com.app.entity.Sale;
import com.app.entity.SaleDetails;

public class SaleMapper {

    public static List<Sale> mapToSales(List<Map<String, Object>> salesDetails, LocalDate date, Long vehicleId, Long routeId,Long driverId,SaleDetails saleDetailData) {
        List<Sale> sales = new ArrayList<>();
        
        for (Map<String, Object> salesDetail : salesDetails) {
            Sale sale = new Sale();
            sale.setDate(date);
            sale.setVehicleNo(vehicleId);
            sale.setKilograms(Double.parseDouble((String)  salesDetail.get("kilograms")));
            sale.setRate(Double.parseDouble((String)  salesDetail.get("rate")));
            sale.setAmount((Integer) salesDetail.get("amount"));
            sale.setDescription((String) salesDetail.get("description"));
            
            String payment = (String )salesDetail.get("payment");
            sale.setPayment(Integer.parseInt(payment));
            sale.setPending((Integer) salesDetail.get("pending"));
            sale.setPaymentMode((String) salesDetail.get("paymentMode"));
            String birds = (String )salesDetail.get("birds");
            sale.setBirds(Integer.parseInt(birds));
            // Set route
            Route route = new Route();
            route.setId(routeId);
            sale.setRoute(route);
            Integer custIdInt = (Integer) salesDetail.get("customerId");
            Long customerId =  new Long(custIdInt);
            Customer customer = new Customer();
            customer.setId(customerId);
            sale.setCustomer(customer);
            
            
            Driver driver = new Driver();
            driver.setId(driverId);
            sale.setDriver(driver);
            
            //sale details data
            sale.setSaleDetails(saleDetailData);
            sales.add(sale);
        }
        
        return sales;
    }
}
