package com.app.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.SaleMapper;
import com.app.dto.SalesBulkEntryDto;
import com.app.dto.SingleSaleEntryDTO;
import com.app.entity.Customer;
import com.app.entity.Driver;
import com.app.entity.Route;
import com.app.entity.Sale;
import com.app.entity.SaleDetails;
import com.app.entity.Vehicle;
import com.app.repository.CustomerRepository;
import com.app.repository.DriverRepository;
import com.app.repository.RouteRepository;
import com.app.repository.SaleDetailsRepository;
import com.app.repository.SaleRepository;
import com.app.repository.VehicleRepository;
import com.app.utility.SmsMessageBuilder;

import cutsomException.ResourceNotFoundException;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Service
public class SalesServiceImpl implements SalesService {

    private static final Logger logger = LoggerFactory.getLogger(SalesServiceImpl.class);

    @Autowired
    private SaleRepository saleRepository;
    
    @Autowired
    private CustomerRepository customerRepository;
    
    @Autowired
    private SaleDetailsRepository saleDetailsRepository;
    
    @Autowired
    private SendSmsService sendSmsService;
    
    @Autowired
    private LedgerService ledgerService;
    
    @Autowired
    private RouteRepository routeRepository;
    
    @Autowired
    private VehicleRepository vehicleRepository;
    
    @Autowired
    private DriverRepository driverRepository;
    
    @PersistenceContext
    private EntityManager entityManager;


    @Transactional
    @Override
    public List<Sale> salesBulkEntry(SalesBulkEntryDto salesBulkEntryDto) {
        logger.info("Entering salesBulkEntry method with parameters: {}", salesBulkEntryDto);
        try {
        	// Create or retrieve SaleDetails
            SaleDetails saleDetails = new SaleDetails();
            saleDetails.setDate(salesBulkEntryDto.getDate());
            
            Vehicle vehicle= new Vehicle();
            vehicle.setId(salesBulkEntryDto.getVehicleNo());
            saleDetails.setVehicle(vehicle);
            
            Route route= new Route();
            route.setId(salesBulkEntryDto.getRoute());
            saleDetails.setRoute(route);
            
            Driver driver= new Driver();
            driver.setId(salesBulkEntryDto.getDriver());
            saleDetails.setDriver(driver);

            saleDetails.setDescription(salesBulkEntryDto.getDescription());
            saleDetails.setTotalBirds(salesBulkEntryDto.getTotalBirds());
            saleDetails.setMortality(salesBulkEntryDto.getMortality());
            saleDetails.setReturnToFarm(salesBulkEntryDto.getReturnToFarm());
            saleDetails.setTotalBirdSale(salesBulkEntryDto.getTotalBirdSale());
            saleDetails.setTotalPaymentReceived(salesBulkEntryDto.getTotalPaymentReceived());
            saleDetails.setTotalAmount(salesBulkEntryDto.getTotalAmount());
            saleDetails.setTotalKilogramSale(salesBulkEntryDto.getTotalKilogramSale());
            saleDetails.setTotalPending(salesBulkEntryDto.getTotalPending());
       
            saleDetails = saveSaleDetails(saleDetails);
            
            
            List<Sale> bulkSalesEntries = SaleMapper.mapToSales(
                    salesBulkEntryDto.getSalesDetails(),
                    salesBulkEntryDto.getDate(),
                    salesBulkEntryDto.getVehicleNo(),
                    salesBulkEntryDto.getRoute(),
                    salesBulkEntryDto.getDriver(),saleDetails
            );
            for (Sale sale : bulkSalesEntries) {
				Sale tempSale = saleRepository.findTopByCustomerIdOrderByIdDesc((Long)sale.getCustomer().getId());
				Integer tempBalPending;
				if(tempSale!=null)
					tempBalPending= tempSale.getBalancePending() == null ? 0 :tempSale.getBalancePending();
				else
					tempBalPending=0;
				Integer balPending= sale.getPending() == null ? 0 :sale.getPending();
				sale.setBalancePending(tempBalPending+balPending);
			}
           List<Sale> savedSales = saleRepository.saveAll(bulkSalesEntries);
            
            
            //To update Balance amount
             List<Map<String, Object>> salesDetails = salesBulkEntryDto.getSalesDetails();
             salesDetails.forEach(map -> {
            	 Integer pending =  (Integer) map.get("pending");
            	// System.out.println("pending"+pending);
            	 if(pending!=null)
            	 {
            		 Integer custIdInt = (Integer) map.get("customerId");
            		 customerRepository.updateBalanceAmount(custIdInt, pending);
            		 logger.info("Balance amount updated for customer Id : {} ", custIdInt);
            	 }
             });
          // Create ledger entry (this will handle backdate recalculation automatically)
             for (Sale savedSale : savedSales) {
             ledgerService.createSaleLedgerEntry(savedSale);
             logger.info("Ledger entry created for sale ID: {}", savedSale.getId());
             }
             entityManager.clear(); // Add this line to synchronize

             if(salesBulkEntryDto.isSendSms()) {
             for (Sale sale : savedSales) {
                try {
                	Optional<Customer> customer = customerRepository.findById(sale.getCustomer().getId());
                     String customerName = customer.get().getName();
                     String phone = customer.get().getMobileNo();
                     double amount = (int) customer.get().getBalanceAmount();
                     String message = SmsMessageBuilder.buildMarathiSms(customerName, amount);

                    // String message = String.format("Hello %s, your sale of ₹%.2f has been recorded. Thank you!", customerName, amount);

                     if (phone != null && !phone.trim().isEmpty()) {
                         sendSmsService.sendSms(customerName, phone,amount,salesBulkEntryDto.getDate());
                        logger.info("📲 SMS trigger sent for customer {} ({})", customerName, phone);
                         sale.setSmsSent(true);
                     } else {
                        logger.warn("⚠️ Customer {} has no phone number, SMS not sent.", customerName);
                     }
                 } catch (Exception ex) {
                     logger.error("❌ Failed to trigger SMS for sale ID {}: {}", sale.getId(), ex.getMessage());
               }
             }
             }
             saleRepository.saveAll(savedSales);
            logger.info("Bulk sales entry created successfully with {} records", savedSales.size());
            return savedSales;
        } catch (Exception e) {
            logger.error("Error during bulk sales entry: {}", e.getMessage(), e);
            throw new RuntimeException("Bulk sales entry failed: " + e.getMessage());
        }
    }
    
    @Transactional
    @Override
    public Sale createSingleSale(SingleSaleEntryDTO saleDTO) {
        logger.info("Creating single sale entry: {}", saleDTO);
        
        try {
            // Validate and fetch entities
            Customer customer = customerRepository.findById(saleDTO.getCustomerId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found with id: " + saleDTO.getCustomerId()));
            
            Route route = routeRepository.findById(saleDTO.getRouteId())
                    .orElseThrow(() -> new ResourceNotFoundException("Route not found with id: " + saleDTO.getRouteId()));
            
            Vehicle vehicle = vehicleRepository.findById(saleDTO.getVehicleId())
                    .orElseThrow(() -> new ResourceNotFoundException("Vehicle not found with id: " + saleDTO.getVehicleId()));
            
            Driver driver = driverRepository.findById(saleDTO.getDriverId())
                    .orElseThrow(() -> new ResourceNotFoundException("Driver not found with id: " + saleDTO.getDriverId()));
            
            // Check credit limit if enabled
            Integer saleAmount = saleDTO.getAmount();
            Integer paymentAmount = saleDTO.getPayment() != null ? saleDTO.getPayment() : 0;
            Integer pendingAmount = saleAmount - paymentAmount;
            
            if (customer.isCreditLimitEnabled() && ledgerService.isCreditLimitExceeded(customer, pendingAmount.doubleValue())) {
                throw new RuntimeException("Credit limit exceeded for customer: " + customer.getName() + 
                        ". Current limit: " + customer.getCreditLimit());
            }
            
            // Create Sale entity
            Sale sale = new Sale();
            sale.setDate(saleDTO.getDate());
            sale.setCustomer(customer);
            sale.setRoute(route);
            sale.setVehicleNo(vehicle.getId());
            sale.setDriver(driver);
            sale.setKilograms(saleDTO.getKilograms());
            sale.setRate(saleDTO.getRate());
            sale.setBirds(saleDTO.getBirds());
            sale.setAmount(saleAmount);
            sale.setPayment(paymentAmount);
            sale.setPending(pendingAmount);
            sale.setPaymentMode(saleDTO.getPaymentMode());
            sale.setDescription(saleDTO.getDescription());
            sale.setObsolete(false);
            sale.setSmsSent(false);
            saleDTO.setSendWAmsg(true);
            // Calculate balance pending (previous balance + current pending)
            Sale previousSale = saleRepository.findTopByCustomerIdOrderByIdDesc(customer.getId());
            Integer previousBalancePending = (previousSale != null && previousSale.getBalancePending() != null) 
                    ? previousSale.getBalancePending() : 0;
            sale.setBalancePending(previousBalancePending + pendingAmount);
            
            // Save the sale
            Sale savedSale = saleRepository.save(sale);
            logger.info("Sale saved with ID: {}", savedSale.getId());
            
            // Create ledger entry (this will handle backdate recalculation automatically)
            ledgerService.createSaleLedgerEntry(savedSale);
            logger.info("Ledger entry created for sale ID: {}", savedSale.getId());
            
            
            if(saleDTO.isSendWAmsg()) {
            
                   try {
                   	Optional<Customer> cust = customerRepository.findById(sale.getCustomer().getId());
                        String customerName = cust.get().getName();
                        String phone = cust.get().getMobileNo();
                        double amount = (int) cust.get().getBalanceAmount();
                        String message = SmsMessageBuilder.buildMarathiSms(customerName, amount);

                       // String message = String.format("Hello %s, your sale of ₹%.2f has been recorded. Thank you!", customerName, amount);

                        if (phone != null && !phone.trim().isEmpty()) {
                            sendSmsService.sendWAmsg(customerName, phone,amount,saleDTO.getDate());
                           logger.info("📲 Whatsapp message sent for customer {} ({})", customerName, phone);
                            sale.setSmsSent(true);
                        } else {
                           logger.warn("⚠️ Customer {} has no phone number, Whatsapp message not sent.", customerName);
                        }
                    } catch (Exception ex) {
                        logger.error("❌ Failed to trigger Whatsapp message for sale ID {}: {}", sale.getId(), ex.getMessage());
                  }
                
                }
            
            
            
            return savedSale;
            
        } catch (ResourceNotFoundException e) {
            logger.error("Resource not found: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error creating single sale: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create sale: " + e.getMessage());
        }
    }

	@Override
	public SaleDetails saveSaleDetails(SaleDetails saleDetails) {
		logger.info("Entering saveSaleDetails method with parameters: {}", saleDetails);
		 try {

			 Optional<SaleDetails> existingSaleDetails = saleDetailsRepository.findByDateAndRouteAndVehicleAndDriver(saleDetails.getDate(), saleDetails.getRoute(), saleDetails.getVehicle(), saleDetails.getDriver());
		        if (existingSaleDetails.isPresent()) {
		            logger.info("Sale details found: {}",existingSaleDetails.get());
		            existingSaleDetails.get().setTotalAmount(existingSaleDetails.get().getTotalAmount()+saleDetails.getTotalAmount());
		            existingSaleDetails.get().setTotalBirdSale(existingSaleDetails.get().getTotalBirdSale()+saleDetails.getTotalBirdSale());
		            existingSaleDetails.get().setTotalKilogramSale(existingSaleDetails.get().getTotalKilogramSale()+saleDetails.getTotalKilogramSale());
		            existingSaleDetails.get().setTotalPaymentReceived(existingSaleDetails.get().getTotalPaymentReceived()+saleDetails.getTotalPaymentReceived());
		            existingSaleDetails.get().setTotalPending(existingSaleDetails.get().getTotalPending()+saleDetails.getTotalPending());
		            existingSaleDetails.get().setTotalAmount(existingSaleDetails.get().getTotalAmount()+saleDetails.getTotalAmount());
		            logger.info("Saving Updated Sale details : {}",existingSaleDetails);
		            return saleDetailsRepository.save(existingSaleDetails.get());
		            
		        } else {
		            logger.warn("No sale details found for the given criteria so saving new saleDetails: {}",saleDetails);
		            return saleDetailsRepository.save(saleDetails);
		           
		        }
			 
	            
	        } catch (Exception e) {
	            logger.error("Error saving sale details", e);
	            throw new RuntimeException("Error saving sale details", e);
	        }
	    }
	
	@Override
	public SaleDetails getSaleDetails(LocalDate date, String route, String vehicle, String driver) {
        logger.info("Fetching sale details for date: {}, route: {}, vehicle: {}, driver: {}", date, route, vehicle, driver);
        Vehicle vehicle1= new Vehicle();
        vehicle1.setId(Long.parseLong(vehicle));

        Route route1= new Route();
        route1.setId(Long.parseLong(route));
        
        Driver driver1= new Driver();
        driver1.setId(Long.parseLong(driver));

        Optional<SaleDetails> saleDetails = saleDetailsRepository.findByDateAndRouteAndVehicleAndDriver(date, route1, vehicle1, driver1);
        if (saleDetails.isPresent()) {
            logger.info("Sale details found: {}");
            return saleDetails.get();
        } else {
            logger.warn("No sale details found for the given criteria");
            return new SaleDetails();
            //throw new ResourceNotFoundException("Sale details not found for the given criteria.");
        }
    }
}

