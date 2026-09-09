package com.app.controller;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.app.dto.SalesBulkEntryDto;
import com.app.dto.SingleSaleEntryDTO;
import com.app.entity.Sale;
import com.app.entity.SaleDetails;
import com.app.repository.CustomerRepository;
import com.app.repository.DriverRepository;
import com.app.repository.SaleRepository;
import com.app.service.SalesService;

import cutsomException.ResourceNotFoundException;

@RestController
@RequestMapping("/user/sales")
public class SaleController {

    private static final Logger logger = LoggerFactory.getLogger(SaleController.class);

    @Autowired
    private SalesService saleService;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private DriverRepository driverRepository;
    

    @GetMapping
    public ResponseEntity<List<Sale>> getAllSales() {
        logger.info("Entering getAllSales method");
        try {
            List<Sale> sales = saleRepository.findAll();
            logger.info("Fetched all sales successfully, total count: {}", sales.size());
            return ResponseEntity.ok(sales);
        } catch (Exception e) {
            logger.error("Error fetching all sales: {}", e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping
    public ResponseEntity<Sale> createSale(@RequestBody Sale sale) {
        logger.info("Entering createSale method with parameters: {}", sale);
        try {
            sale.setCustomer(customerRepository.findById(sale.getCustomer().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found")));
            sale.setDriver(driverRepository.findById(sale.getDriver().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Driver not found")));
            Sale savedSale = saleRepository.save(sale);
            logger.info("Created sale with ID: {}", savedSale.getId());
            return ResponseEntity.ok(savedSale);
        } catch (ResourceNotFoundException e) {
            logger.warn("Resource not found: {}", e.getMessage());
            return ResponseEntity.status(404).body(null);
        } catch (Exception e) {
            logger.error("Error creating sale: {}", e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    @PostMapping("/bulk")
    public ResponseEntity<List<Sale>> salesBulkEntry(@RequestBody SalesBulkEntryDto salesBulkEntryDto) {
        logger.info("Entering salesBulkEntry method with parameters: {}", salesBulkEntryDto);
        try {
            List<Sale> result = saleService.salesBulkEntry(salesBulkEntryDto);
            logger.info("Bulk sales entry created successfully with {} records", result.size());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            logger.error("Error during bulk sales entry: {}", e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }
    
    /**
     * New: Create single sale entry with automatic ledger creation
     */
    @PostMapping("/single")
    public ResponseEntity<?> createSingleSale(@RequestBody SingleSaleEntryDTO saleDTO) {
        logger.info("Creating single sale entry: {}", saleDTO);
        try {
            Sale sale = saleService.createSingleSale(saleDTO);
            logger.info("Single sale created with ID: {}", sale.getId());
            return ResponseEntity.ok(sale);
        } catch (Exception e) {
            logger.error("Error creating single sale: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to create sale: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<Sale> getSaleById(@PathVariable Long id) {
        logger.info("Entering getSaleById method with ID: {}", id);
        try {
            Sale sale = saleRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Sale not found with id " + id));
            logger.info("Fetched sale with ID: {}", id);
            return ResponseEntity.ok(sale);
        } catch (ResourceNotFoundException e) {
            logger.warn("Resource not found: {}", e.getMessage());
            return ResponseEntity.status(404).body(null);
        } catch (Exception e) {
            logger.error("Error fetching sale with ID {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Sale> updateSale(@PathVariable Long id, @RequestBody Sale saleDetails) {
        logger.info("Entering updateSale method with ID: {} and parameters: {}", id, saleDetails);
        try {
            Sale sale = saleRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Sale not found with id " + id));

            sale.setDate(saleDetails.getDate());
            sale.setCustomer(customerRepository.findById(saleDetails.getCustomer().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Customer not found")));
            sale.setDriver(driverRepository.findById(saleDetails.getDriver().getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Driver not found")));
            sale.setKilograms(saleDetails.getKilograms());
            sale.setRate(saleDetails.getRate());
            sale.setAmount(saleDetails.getAmount());
            sale.setDescription(saleDetails.getDescription());

            Sale updatedSale = saleRepository.save(sale);
            logger.info("Updated sale with ID: {}", id);
            return ResponseEntity.ok(updatedSale);
        } catch (ResourceNotFoundException e) {
            logger.warn("Resource not found: {}", e.getMessage());
            return ResponseEntity.status(404).body(null);
        } catch (Exception e) {
            logger.error("Error updating sale with ID {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSale(@PathVariable Long id) {
        logger.info("Entering deleteSale method with ID: {}", id);
        try {
            Sale sale = saleRepository.findById(id)
                    .orElseThrow(() -> new ResourceNotFoundException("Sale not found with id " + id));
            saleRepository.delete(sale);
            logger.info("Deleted sale with ID: {}", id);
            return ResponseEntity.noContent().build();
        } catch (ResourceNotFoundException e) {
            logger.warn("Resource not found: {}", e.getMessage());
            return ResponseEntity.status(404).build();
        } catch (Exception e) {
            logger.error("Error deleting sale with ID {}: {}", id, e.getMessage(), e);
            return ResponseEntity.status(500).build();
        }
    }
    
    @PostMapping("/saveDetails")
    public ResponseEntity<SaleDetails> createSaleDetails(@RequestBody SaleDetails saleDetails) {
        try {
            SaleDetails savedSaleDetails = saleService.saveSaleDetails(saleDetails);
            return ResponseEntity.ok(savedSaleDetails);
        } catch (Exception e) {
            logger.error("Error creating sale details", e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Error creating sale details", e);
        }
    }
    @GetMapping("/saleDetails")
    public ResponseEntity<?> getSaleDetails(
            @RequestParam("date") LocalDate date,
            @RequestParam("route") String route,
            @RequestParam("vehicle") String vehicle,
            @RequestParam("driver") String driver) {
        
        try {
            logger.info("Request received for sale details with date: {}, route: {}, vehicle: {}, driver: {}", date, route, vehicle, driver);
            SaleDetails saleDetails = saleService.getSaleDetails(date, route, vehicle, driver);
            return new ResponseEntity<>(saleDetails, HttpStatus.OK);
        } catch (ResourceNotFoundException e) {
            logger.error("Error fetching sale details: {}", e.getMessage());
            return new ResponseEntity<>(e.getMessage(), HttpStatus.NOT_FOUND);
        } catch (Exception e) {
            logger.error("Unexpected error: {}", e.getMessage());
            return new ResponseEntity<>("An unexpected error occurred", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
