package com.app.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.LedgerMigrationResponseDTO;
import com.app.utility.MoneyRules;
import com.app.entity.Customer;
import com.app.entity.CustomerLedger;
import com.app.entity.Sale;
import com.app.repository.CustomerLedgerRepository;
import com.app.repository.CustomerRepository;
import com.app.repository.SaleRepository;

@Service
public class LedgerMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(LedgerMigrationService.class);

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private SaleRepository saleRepository;

    @Autowired
    private CustomerLedgerRepository ledgerRepository;

    @Autowired
    private LedgerService ledgerService;

    /**
     * Migrate existing sales data to ledger system
     * This should be run once after deploying the new ledger system
     */
    @Transactional
    public LedgerMigrationResponseDTO migrateExistingSalesToLedger() {
        logger.info("Starting migration of existing sales to ledger system");
        
        LedgerMigrationResponseDTO response = new LedgerMigrationResponseDTO();
        int customersProcessed = 0;
        int salesMigrated = 0;
        int ledgerEntriesCreated = 0;
        
        try {
            // Check if ledger already has data
            long existingLedgerCount = ledgerRepository.count();
            if (existingLedgerCount > 0) {
                logger.warn("Ledger already contains {} entries. Skipping migration to avoid duplicates.", existingLedgerCount);
                response.setSuccess(false);
                response.setMessage("Ledger migration skipped - ledger already contains data");
                return response;
            }
            
            // Get all customers
            List<Customer> customers = customerRepository.findAll();
            logger.info("Found {} customers to process", customers.size());
            
            for (Customer customer : customers) {
                try {
                    logger.info("Processing customer: {} (ID: {})", customer.getName(), customer.getId());
                    
                    // The opening balance is what the customer owed BEFORE their
                    // first recorded sale, derived by removing the net of all
                    // recorded sales from the balance they carry today:
                    //
                    //     opening = balance_amount - SUM(sale.amount - sale.payment)
                    //
                    // Using balance_amount directly as the opening, as this did
                    // originally, double-counts: that balance already reflects
                    // every sale replayed below. On production data it overstated
                    // total receivables by 4,950,210 rupees - about 24% - and the
                    // result still reconciled internally, so the error was
                    // invisible from the ledger alone.
                    //
                    // Deriving it means replaying the sales reproduces
                    // balance_amount exactly: the backfill explains the existing
                    // balances instead of changing them.
                    List<Sale> customerSales = saleRepository.findByCustomerOrderByDateAsc(customer);

                    BigDecimal netOfSales = BigDecimal.ZERO;
                    for (Sale priorSale : customerSales) {
                        netOfSales = netOfSales
                                .add(MoneyRules.money(priorSale.getAmount()))
                                .subtract(MoneyRules.money(priorSale.getPayment()));
                    }

                    BigDecimal openingBalance = MoneyRules.money(
                            MoneyRules.money(customer.getBalanceAmount()).subtract(netOfSales));

                    if (openingBalance.signum() != 0) {
                        LocalDate openingDate = customerSales.isEmpty() ?
                                LocalDate.now().minusDays(1) :
                                customerSales.get(0).getDate().minusDays(1);

                        ledgerService.createOpeningBalanceEntry(customer, openingBalance, openingDate);
                        ledgerEntriesCreated++;
                        logger.info("Opening balance for customer {}: {} (carries {}, net of sales {})",
                                customer.getName(), openingBalance,
                                MoneyRules.money(customer.getBalanceAmount()), netOfSales);
                    }
                    
                    // Get all sales for this customer ordered by date
                    List<Sale> sales = saleRepository.findByCustomerOrderByDateAsc(customer);
                    logger.info("Found {} sales for customer {}", sales.size(), customer.getName());
                    
                    // Create ledger entries for each sale
                    for (Sale sale : sales) {
                        try {
                            CustomerLedger ledgerEntry = new CustomerLedger();
                            ledgerEntry.setCustomer(customer);
                            ledgerEntry.setTransactionDate(sale.getDate());
                            ledgerEntry.setTransactionType(CustomerLedger.TransactionType.SALE);
                            ledgerEntry.setReferenceType("SALE");
                            ledgerEntry.setReferenceId(sale.getId());
                            
                            BigDecimal saleAmount = MoneyRules.money(sale.getAmount());
                            BigDecimal paymentAmount = MoneyRules.money(sale.getPayment());
                            
                            ledgerEntry.setDebitAmount(saleAmount);
                            ledgerEntry.setCreditAmount(paymentAmount);
                            ledgerEntry.setPaymentMode(sale.getPaymentMode());
                            ledgerEntry.setDescription("Migrated Sale - " + 
                                    (sale.getBirds() != null ? sale.getBirds() + " birds, " : "") + 
                                    (sale.getKilograms() != null ? sale.getKilograms() + " kg" : ""));
                            ledgerEntry.setBackdated(false); // Historical data, not backdated
                            
                            // Running balance will be calculated during recalculation
                            ledgerEntry.setRunningBalance(MoneyRules.money(BigDecimal.ZERO));
                            
                            ledgerRepository.save(ledgerEntry);
                            ledgerEntriesCreated++;
                            salesMigrated++;
                            
                        } catch (Exception e) {
                            logger.error("Error creating ledger entry for sale {}: {}", sale.getId(), e.getMessage());
                        }
                    }
                    
                    // Now recalculate all balances for this customer
                    ledgerService.recalculateAllBalances(customer);
                    customersProcessed++;
                    
                    logger.info("Completed migration for customer {}", customer.getName());
                    
                } catch (Exception e) {
                    logger.error("Error processing customer {}: {}", customer.getId(), e.getMessage(), e);
                }
            }
            
            response.setSuccess(true);
            response.setMessage("Migration completed successfully");
            response.setCustomersProcessed(customersProcessed);
            response.setSalesMigrated(salesMigrated);
            response.setLedgerEntriesCreated(ledgerEntriesCreated);
            
            logger.info("Migration complete. Customers: {}, Sales: {}, Ledger Entries: {}", 
                    customersProcessed, salesMigrated, ledgerEntriesCreated);
            
        } catch (Exception e) {
            logger.error("Migration failed: {}", e.getMessage(), e);
            response.setSuccess(false);
            response.setMessage("Migration failed");
            response.setError(e.getMessage());
        }
        
        return response;
    }
    
    /**
     * Check migration status
     */
    public String getMigrationStatus() {
        long ledgerCount = ledgerRepository.count();
        long salesCount = saleRepository.count();
        long customerCount = customerRepository.count();
        
        return String.format("Ledger Entries: %d, Sales: %d, Customers: %d", 
                ledgerCount, salesCount, customerCount);
    }
}
