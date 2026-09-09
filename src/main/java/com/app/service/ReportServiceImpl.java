package com.app.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.app.dto.ReportRequestDTO;
import com.app.dto.ReportResponseDTO;
import com.app.repository.PurchaseRepository;
import com.app.repository.SaleRepository;

@Service
public class ReportServiceImpl implements ReportService {

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Override
    public ReportResponseDTO generateReport(ReportRequestDTO request) {
        List<LinkedHashMap<String, Object>> reportData = new ArrayList<>();

        if ("sale".equalsIgnoreCase(request.getReportType())) {
            reportData = generateSalesReport(request);
        } else if ("purchase".equalsIgnoreCase(request.getReportType())) {
            reportData = generatePurchaseReport(request);
        }

        ReportResponseDTO response = new ReportResponseDTO();
        response.setData(reportData);
        response.setErrorMessage("Report generated successfully.");
        return response;
    }

    private List<LinkedHashMap<String, Object>> generateSalesReport(ReportRequestDTO request) {
        List<LinkedHashMap<String, Object>> sales = new ArrayList<>();
        List<Map<String, Object>> rawSalesData = new ArrayList<>();
        List<String> columnOrder= new ArrayList<>();

        switch (request.getSubType()) {
            case "routes":    
                 if (request.getSubTypeId().isEmpty()) {
 				    rawSalesData = saleRepository.findByAllRouteAndDateBetween(request.getStartDate(), request.getEndDate());
 				   columnOrder = Arrays.asList("ROUTE", "VEHICLE", "DRIVER", "CITY","CUSTOMER NAME", 
				            "SHOP NAME", "SALE DATE", "BIRDS", "WEIGHT", 
				            "RATE", "AMOUNT", "PAYMENT RECEIVED", 
				            "PAYMENT PENDING", "TOTAL BALANCE", "DESCRIPTION");
 				} else {
 					 
 				    rawSalesData = saleRepository.findByRouteAndDateBetween(Long.parseLong(request.getSubTypeId()), request.getStartDate(), request.getEndDate());
 				    columnOrder = Arrays.asList("ROUTE", "VEHICLE", "DRIVER","CITY", "CUSTOMER NAME", 
 				            "SHOP NAME", "SALE DATE", "BIRDS", "WEIGHT", 
 				            "RATE", "AMOUNT", "PAYMENT RECEIVED", 
 				            "PAYMENT PENDING", "TOTAL BALANCE", "DESCRIPTION");
 				}

                break;
			case "customers":
	
				if (request.getSubTypeId().isEmpty()) {
				    rawSalesData = saleRepository.findSaleReportByDateRange(request.getStartDate(), request.getEndDate());
				    columnOrder = Arrays.asList("ROUTE","CITY", "CUSTOMER NAME", "SHOP NAME", "BALANCE PENDING");
				} else {
				    rawSalesData = saleRepository.findSaleReportByIdAndDateRange(Long.parseLong(request.getSubTypeId()),
				            request.getStartDate(), request.getEndDate());
				    columnOrder = Arrays.asList("ROUTE", "VEHICLE", "DRIVER", "CUSTOMER NAME", 
				            "SHOP NAME", "SALE DATE", "BIRDS", "WEIGHT", 
				            "RATE", "AMOUNT", "PAYMENT RECEIVED", 
				            "PAYMENT PENDING", "BALANCE PENDING", "DESCRIPTION");
				}

				break;
            case "vehicles":
             //   sales = saleRepository.findByVehicleAndDateBetween(request.getVehicleId(), request.getStartDate(), request.getEndDate());
                break;
            case "drivers":
              // = saleRepository.findByDriverAndDateBetween(request.getDriverId(), request.getStartDate(), request.getEndDate());
                break;
            case "cities":
               // = saleRepository.findByCityAndDateBetween(request.getCityId(), request.getStartDate(), request.getEndDate());
                break;
            case "all":
              //  sales = saleRepository.findAllByDateBetween(request.getStartDate(), request.getEndDate());
                break;
            default:
                break;
        }
        // Define column order here
        
        

        // Process data to maintain column order
        for (Map<String, Object> row : rawSalesData) {
            LinkedHashMap<String, Object> orderedRow = new LinkedHashMap<>();
            for (String column : columnOrder) {
                orderedRow.put(column, row.get(column));
            }
            sales.add(orderedRow);
        }

        return sales;
    }

    private List<LinkedHashMap<String, Object>> generatePurchaseReport(ReportRequestDTO request) {
        // Similar to sales report logic, implement the purchase report generation here
        // based on request parameters like supplier, date range, etc.
        List<LinkedHashMap<String, Object>> reportData = new ArrayList<>();
        // Implementation goes here...
        return reportData;
    }
}

