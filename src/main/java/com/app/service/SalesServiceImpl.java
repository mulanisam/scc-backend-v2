package com.app.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.SaleLineDto;
import com.app.dto.SaleMapper;
import com.app.dto.SalesBulkEntryDto;
import com.app.dto.TripContextDTO;
import com.app.dto.SingleSaleEntryDTO;
import com.app.entity.Customer;
import com.app.entity.MessageOutbox.Channel;
import com.app.entity.MessageOutbox.MessageType;
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
import com.app.utility.MoneyRules;
import com.app.utility.SmsMessageBuilder;

import com.app.exception.ResourceNotFoundException;

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
    private MessagingService messagingService;
    
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


    /**
     * Rejects a bulk entry that does not add up, before anything is written.
     *
     * Two checks the API previously left entirely to the browser:
     * every bird loaded must be sold, dead or returned; and the amount the
     * client calculated must match what the server calculates from kilograms
     * and rate. A mismatch means the two sides disagree about the rounding
     * rule, which is how the same sale came to be booked at different amounts
     * from different screens - so it fails loudly rather than being overwritten
     * in silence.
     */
    private void validateBulkEntry(SalesBulkEntryDto dto) {
        List<SaleLineDto> lines = dto.getSalesDetails();
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A sale entry must contain at least one customer line.");
        }

        if (dto.getDate() == null) {
            throw new IllegalArgumentException("A sale entry must have a date.");
        }

        // A sale cannot be recorded before it happens. Enforced here as well as
        // in the browser, because the API is reachable directly.
        if (dto.getDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Sale date " + dto.getDate()
                    + " is in the future. Future-dated sales cannot be saved.");
        }

        int soldBirds = 0;
        for (SaleLineDto line : lines) {
            if (line.getCustomerId() == null) {
                throw new IllegalArgumentException("Every sale line must name a customer.");
            }
            soldBirds += line.getBirds() == null ? 0 : line.getBirds();

            BigDecimal expected = MoneyRules.calculateAmount(line.getKilograms(), line.getRate());
            if (line.getAmount() != null && !MoneyRules.amountsMatch(expected, line.getAmount())) {
                throw new IllegalArgumentException(String.format(
                        "Amount mismatch for customer %d: %s kg at rate %s is %s, but %s was submitted.",
                        line.getCustomerId(), line.getKilograms(), line.getRate(),
                        expected.toPlainString(), line.getAmount().toPlainString()));
            }
        }

        MoneyRules.BirdReconciliation birds = MoneyRules.reconcileBirds(
                dto.getTotalBirds(), soldBirds, dto.getMortality(), dto.getReturnToFarm());

        if (!birds.isBalanced()) {
            throw new IllegalArgumentException(String.format(
                    "Bird count does not balance: %d loaded but %d accounted for (%s).",
                    birds.getTotalBirds(), birds.getAccountedFor(), birds.getMessage()));
        }
    }

    /**
     * Refuses the whole entry if any customer on it would go past their credit
     * limit, before a single row is written.
     *
     * Called alongside validateBulkEntry and outside the try below, for the same
     * reason: that block turns anything it catches into a plain RuntimeException,
     * so a refusal raised inside it reached the operator as a 500 reading "Bulk
     * sales entry failed" rather than the name of the customer who is over.
     *
     * Two things this has to get right that a per-line check would not. The lines
     * are summed per customer first, because the same customer legitimately appears
     * more than once on one trip - it has happened on 175 trips in this database -
     * and checking each line against the opening balance alone would let two lines
     * through that together breach the limit. And only customers who actually have
     * a limit enabled are examined, so the common case adds one batched lookup.
     */
    private void enforceCreditLimits(SalesBulkEntryDto dto) {
        Map<Long, BigDecimal> pendingByCustomer = new LinkedHashMap<>();
        for (SaleLineDto line : dto.getSalesDetails()) {
            // Derived the same way SaleMapper will derive it, so the check tests
            // the figure that is about to be stored.
            BigDecimal pending = MoneyRules.calculatePending(
                    MoneyRules.calculateAmount(line.getKilograms(), line.getRate()),
                    MoneyRules.money(line.getPayment()));
            if (pending.signum() <= 0) {
                continue;
            }
            pendingByCustomer.merge(line.getCustomerId(), pending, BigDecimal::add);
        }
        if (pendingByCustomer.isEmpty()) {
            return;
        }

        for (Customer customer : customerRepository.findAllById(pendingByCustomer.keySet())) {
            if (!customer.isCreditLimitEnabled() || customer.getCreditLimit() == null) {
                continue;
            }
            BigDecimal additional = pendingByCustomer.get(customer.getId());
            if (ledgerService.isCreditLimitExceeded(customer, additional)) {
                throw new IllegalStateException(String.format(
                        "%s has reached their credit limit: balance %s plus %s on this entry exceeds the limit of %s.",
                        customer.getName(),
                        ledgerService.getCurrentBalance(customer).toPlainString(),
                        additional.toPlainString(),
                        MoneyRules.money(customer.getCreditLimit()).toPlainString()));
            }
        }
    }

    /**
     * Queues one message per customer for the day's trading.
     *
     * Per customer, not per sale row. The old code sent from inside the save loop,
     * once per row, so a customer with two lines on one trip got two messages -
     * which happens on 175 trips in this data - and each quoted the running balance
     * as it stood after that line, so the first was already out of date when the
     * second arrived. Summing the day's lines and quoting the closing balance is
     * both cheaper and correct.
     *
     * Nothing here calls the provider. MessagingService writes a row and returns, so
     * the sale is not waiting on an HTTP request and cannot be rolled back by a
     * messaging failure; the dispatcher picks the queue up afterwards.
     */
    private void queueDailyMessages(List<Sale> sales, LocalDate date) {
        Map<Long, List<Sale>> byCustomer = new LinkedHashMap<>();
        for (Sale sale : sales) {
            byCustomer.computeIfAbsent(sale.getCustomer().getId(), key -> new ArrayList<>()).add(sale);
        }

        for (Map.Entry<Long, List<Sale>> entry : byCustomer.entrySet()) {
            try {
                Customer customer = customerRepository.findById(entry.getKey()).orElse(null);
                if (customer == null) {
                    continue;
                }

                long birds = 0;
                BigDecimal kilograms = BigDecimal.ZERO;
                BigDecimal amount = BigDecimal.ZERO;
                BigDecimal paid = BigDecimal.ZERO;
                for (Sale sale : entry.getValue()) {
                    birds += sale.getBirds() == null ? 0 : sale.getBirds();
                    kilograms = kilograms.add(MoneyRules.weight(sale.getKilograms()));
                    amount = amount.add(MoneyRules.money(sale.getAmount()));
                    paid = paid.add(MoneyRules.money(sale.getPayment()));
                }

                // The ledger's figure, which createSaleLedgerEntry has just written.
                BigDecimal balance = MoneyRules.money(customer.getBalanceAmount());

                // The message has to match the channel's approved template. The DLT
                // template used for SMS takes three variables - name, date, balance -
                // so the detailed seven-variable summary can only go over WhatsApp,
                // and only once its own template is approved. Sending the wrong count
                // is rejected by the provider.
                SmsMessageBuilder.Message message = messagingService.currentChannel() == Channel.WHATSAPP
                        ? SmsMessageBuilder.dailySaleSummary(
                                customer.getName(), date, birds, kilograms, amount, paid, balance)
                        : SmsMessageBuilder.dailyBalance(customer.getName(), date, balance);

                messagingService.enqueue(customer, MessageType.DAILY_SALE_SUMMARY, date,
                        null, message.variables(), message.body());

                entry.getValue().forEach(sale -> sale.setSmsSent(true));

            } catch (Exception ex) {
                // A message that cannot be queued must never fail the sale. The
                // outbox is the record, so a gap in it is visible afterwards.
                logger.error("Could not queue the daily message for customer {}: {}",
                        entry.getKey(), ex.getMessage());
            }
        }
    }

    @Transactional
    @Override
    public List<Sale> salesBulkEntry(SalesBulkEntryDto salesBulkEntryDto) {
        logger.info("Entering salesBulkEntry method with parameters: {}", salesBulkEntryDto);

        // Validated outside the try below, which wraps everything in a plain
        // RuntimeException and would otherwise turn these rejections into a 500
        // with no usable message for the operator.
        validateBulkEntry(salesBulkEntryDto);
        enforceCreditLimits(salesBulkEntryDto);

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
            List<Sale> savedSales = saleRepository.saveAll(bulkSalesEntries);

            // The ledger is the only thing that maintains the customer balance.
            //
            // This block used to also increment customer.balance_amount directly
            // through updateBalanceAmount, and then createSaleLedgerEntry set the
            // same column to an absolute figure derived from the ledger: two
            // writers for one number, where which one won depended on flush order.
            // The entityManager.clear() that used to close this block was there to
            // survive that. The ledger's figure is the correct one, so the
            // increment is gone rather than the two being kept in step.
            for (Sale savedSale : savedSales) {
                ledgerService.createSaleLedgerEntry(savedSale);
                logger.debug("Ledger entry created for sale ID: {}", savedSale.getId());
            }

            if (salesBulkEntryDto.isSendSms()) {
                queueDailyMessages(savedSales, salesBulkEntryDto.getDate());
            }

            saleRepository.saveAll(savedSales);
            logger.info("Bulk sales entry created successfully with {} records", savedSales.size());
            return savedSales;
        } catch (IllegalArgumentException | IllegalStateException | ResourceNotFoundException e) {
            // Business rejections keep their type so the exception handler can
            // report them as 400 with the message intact. IllegalStateException is
            // in the list because that is what a credit-limit refusal throws, and
            // without it the operator saw "Bulk sales entry failed" as a 500
            // instead of being told which customer is over their limit.
            throw e;
        } catch (Exception e) {
            // The cause is preserved rather than flattened into a string, so the
            // original stack trace survives to the log.
            logger.error("Error during bulk sales entry", e);
            throw new RuntimeException("Bulk sales entry failed", e);
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
            
            // Calculate the amount here rather than trusting the request, and
            // reject a disagreement instead of silently overwriting it.
            BigDecimal saleAmount = MoneyRules.calculateAmount(saleDTO.getKilograms(), saleDTO.getRate());
            if (saleDTO.getAmount() != null && !MoneyRules.amountsMatch(saleAmount, saleDTO.getAmount())) {
                throw new IllegalArgumentException(String.format(
                        "Amount mismatch: %s kg at rate %s is %s, but %s was submitted.",
                        saleDTO.getKilograms(), saleDTO.getRate(),
                        saleAmount.toPlainString(), saleDTO.getAmount().toPlainString()));
            }

            BigDecimal paymentAmount = MoneyRules.money(saleDTO.getPayment());
            BigDecimal pendingAmount = MoneyRules.calculatePending(saleAmount, paymentAmount);

            if (customer.isCreditLimitEnabled() && ledgerService.isCreditLimitExceeded(customer, pendingAmount)) {
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

            // Save the sale
            Sale savedSale = saleRepository.save(sale);
            logger.info("Sale saved with ID: {}", savedSale.getId());
            
            // Create ledger entry (this will handle backdate recalculation automatically)
            ledgerService.createSaleLedgerEntry(savedSale);
            logger.info("Ledger entry created for sale ID: {}", savedSale.getId());
            
            
            // Same path as bulk entry: one message per customer for the day, queued
            // rather than sent, so the outbox is the record either way.
            if (saleDTO.isSendWAmsg()) {
                queueDailyMessages(List.of(savedSale), saleDTO.getDate());
            }

            return savedSale;
            
        } catch (IllegalArgumentException | ResourceNotFoundException e) {
            // Business rejections keep their type so they surface as 400 with
            // the message, not as an opaque 500.
            throw e;
        } catch (Exception e) {
            logger.error("Error creating single sale", e);
            throw new RuntimeException("Failed to create sale", e);
        }
    }

	@Override
	public SaleDetails saveSaleDetails(SaleDetails saleDetails) {
		logger.info("Entering saveSaleDetails method with parameters: {}", saleDetails);
		 try {

			 Optional<SaleDetails> existingSaleDetails = saleDetailsRepository.findByDateAndRouteAndVehicleAndDriver(saleDetails.getDate(), saleDetails.getRoute(), saleDetails.getVehicle(), saleDetails.getDriver());
		        if (existingSaleDetails.isPresent()) {
		            logger.info("Sale details found: {}",existingSaleDetails.get());
		            existingSaleDetails.get().setTotalAmount(MoneyRules.money(MoneyRules.money(existingSaleDetails.get().getTotalAmount()).add(MoneyRules.money(saleDetails.getTotalAmount()))));
		            existingSaleDetails.get().setTotalBirdSale((existingSaleDetails.get().getTotalBirdSale()==null?0:existingSaleDetails.get().getTotalBirdSale())+(saleDetails.getTotalBirdSale()==null?0:saleDetails.getTotalBirdSale()));
		            existingSaleDetails.get().setTotalKilogramSale(MoneyRules.weight(MoneyRules.weight(existingSaleDetails.get().getTotalKilogramSale()).add(MoneyRules.weight(saleDetails.getTotalKilogramSale()))));
		            existingSaleDetails.get().setTotalPaymentReceived(MoneyRules.money(MoneyRules.money(existingSaleDetails.get().getTotalPaymentReceived()).add(MoneyRules.money(saleDetails.getTotalPaymentReceived()))));
		            existingSaleDetails.get().setTotalPending(MoneyRules.money(MoneyRules.money(existingSaleDetails.get().getTotalPending()).add(MoneyRules.money(saleDetails.getTotalPending()))));
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

    @Override
    public TripContextDTO getTripContext(LocalDate date, Long routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new ResourceNotFoundException("Route not found with id: " + routeId));

        TripContextDTO context = new TripContextDTO();
        context.setDate(date);
        context.setRouteId(routeId);

        List<SaleDetails> existing = saleDetailsRepository.findByDateAndRoute(date, route);
        context.setExistingTripCount(existing.size());
        context.setDuplicate(!existing.isEmpty());

        int birds = 0;
        BigDecimal amount = BigDecimal.ZERO;
        for (SaleDetails trip : existing) {
            birds += trip.getTotalBirdSale() == null ? 0 : trip.getTotalBirdSale();
            amount = amount.add(MoneyRules.money(trip.getTotalAmount()));
        }
        context.setExistingBirds(birds);
        context.setExistingAmount(MoneyRules.money(amount));

        saleDetailsRepository.findTopByRouteOrderByDateDescIdDesc(route).ifPresent(latest -> {
            context.setLastSaleDate(latest.getDate());
            if (latest.getDate() != null && date != null && date.isBefore(latest.getDate())) {
                context.setDaysBeforeLastSale(
                        (int) java.time.temporal.ChronoUnit.DAYS.between(date, latest.getDate()));
            }
        });

        logger.debug("Trip context for {} on route {}: duplicate={}, lastSale={}",
                date, routeId, context.isDuplicate(), context.getLastSaleDate());

        return context;
    }
}
