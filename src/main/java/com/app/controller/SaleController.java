package com.app.controller;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
import com.app.dto.TripContextDTO;
import com.app.dto.SingleSaleEntryDTO;
import com.app.entity.Sale;
import com.app.entity.SaleDetails;
import com.app.repository.CustomerRepository;
import com.app.repository.DriverRepository;
import com.app.repository.SaleRepository;
import com.app.service.SalesService;

import com.app.exception.ResourceNotFoundException;

import jakarta.validation.Valid;

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
    

    /**
     * A page of sales, newest first.
     *
     * Paged, and it has to be. This used to be findAll() with no bound: measured against
     * production it returned <b>1.13 GB in 12.8 seconds</b> - 55,528 sales, each serialised
     * with its customer, that customer's city, the city's route and the route's own list of
     * cities. Two of those at once would have exhausted the heap. Nothing in the
     * application calls it, so the cap costs nothing and removes a way to take the service
     * down with one request.
     *
     * @param size rows to return, capped at 500
     */
    @GetMapping
    public ResponseEntity<List<Sale>> getAllSales(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "100") int size) {

        int bounded = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        List<Sale> sales = saleRepository.findAll(
                PageRequest.of(Math.max(0, page), bounded, Sort.by(Sort.Direction.DESC, "id")))
                .getContent();

        logger.info("Returning {} sales (page {}, size {})", sales.size(), page, bounded);
        return ResponseEntity.ok(sales);
    }

    /** A page of sales is capped here; the whole table is 55,528 rows and 1.13 GB of JSON. */
    private static final int MAX_PAGE_SIZE = 500;

    /*
     * The three endpoints below wrote a sale without touching the ledger, and each was
     * measured doing real damage:
     *
     *   POST   created a sale with no ledger row and no balance change - the customer owed
     *          money the ledger did not know about
     *   PUT    changed an amount from 1200 to 1300 and left the ledger row saying 1200
     *   DELETE removed sale 56388 and left ledger row 112818 pointing at it, with the
     *          balance still inflated by the deleted sale's 1,000
     *
     * That contradicts the one rule the whole ledger redesign rests on: the ledger is the
     * only thing that maintains a customer's balance. They refuse now rather than corrupt.
     * Nothing in the application calls them - sales are entered through /bulk and /single,
     * both of which post correctly - so refusing costs nothing and closes a route by which
     * 2 crore of receivables could be silently put out of step.
     *
     * Deleting them outright is the next step; they are kept as explicit refusals so that
     * anything still calling them gets told where to go instead of quietly succeeding.
     */
    @PostMapping
    public ResponseEntity<Sale> createSale(@RequestBody Sale sale) {
        logger.warn("Refused POST /user/sales: this endpoint does not post to the ledger");
        throw new IllegalStateException(
                "Sales are not recorded through this endpoint, because it does not post to the"
                        + " ledger and the balance would not move. Use /user/sales/bulk for a"
                        + " trip or /user/sales/single for one customer.");
    }

    /**
     * Exceptions deliberately propagate to GlobalExceptionHandler rather than
     * being caught here. These methods previously swallowed everything and
     * returned a bare 500, which would hide the validation messages the service
     * raises - "bird count does not balance", "amount mismatch" - and leave the
     * operator with nothing to act on. The handler turns those into a 400
     * carrying the message, and anything genuinely unexpected into a 500 with a
     * log reference.
     */
    @PostMapping("/bulk")
    public ResponseEntity<List<Sale>> salesBulkEntry(@Valid @RequestBody SalesBulkEntryDto salesBulkEntryDto) {
        logger.info("Bulk sales entry for {} on route {}",
                salesBulkEntryDto.getDate(), salesBulkEntryDto.getRoute());

        List<Sale> result = saleService.salesBulkEntry(salesBulkEntryDto);
        logger.info("Bulk sales entry created successfully with {} records", result.size());
        return ResponseEntity.ok(result);
    }

    /**
     * Create single sale entry with automatic ledger creation.
     */
    @PostMapping("/single")
    public ResponseEntity<Sale> createSingleSale(@Valid @RequestBody SingleSaleEntryDTO saleDTO) {
        logger.info("Single sale entry for customer {} on {}", saleDTO.getCustomerId(), saleDTO.getDate());

        Sale sale = saleService.createSingleSale(saleDTO);
        logger.info("Single sale created with ID: {}", sale.getId());
        return ResponseEntity.ok(sale);
    }

    /**
     * One sale.
     *
     * No try/catch: the handler turns the ResourceNotFoundException into a 404 whose body
     * names the id. The block here returned {@code status(404).body(null)} - an empty body,
     * so a screen had nothing to show and could only print its own guess.
     */
    @GetMapping("/{id}")
    public ResponseEntity<Sale> getSaleById(@PathVariable Long id) {
        return ResponseEntity.ok(saleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sale " + id + " was not found.")));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Sale> updateSale(@PathVariable Long id, @RequestBody Sale saleDetails) {
        logger.warn("Refused PUT /user/sales/{}: this endpoint does not update the ledger", id);
        throw new IllegalStateException(
                "A sale cannot be edited here: the change would not reach the ledger and the"
                        + " balance would keep the old amount. Enter a correcting entry instead.");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteSale(@PathVariable Long id) {
        logger.warn("Refused DELETE /user/sales/{}: this endpoint leaves the ledger behind", id);
        throw new IllegalStateException(
                "A sale cannot be deleted here: its ledger row would be left pointing at a row"
                        + " that no longer exists and the balance would stay as it was. Post a"
                        + " correcting entry, which keeps both the original and the correction.");
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
    /**
     * Context the entry screen needs before it submits: whether a trip already
     * exists for this date and route, and when the route last had a sale.
     *
     * The client cannot answer either question on its own, so without this it
     * could not warn about a duplicate submission or ask the operator to confirm
     * a backdated entry.
     */
    @GetMapping("/tripContext")
    public ResponseEntity<TripContextDTO> getTripContext(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("route") Long routeId) {

        logger.info("Trip context requested for date {} on route {}", date, routeId);
        return ResponseEntity.ok(saleService.getTripContext(date, routeId));
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
