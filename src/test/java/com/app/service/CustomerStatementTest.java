package com.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Limit;

import com.app.dto.CustomerLedgerDTO;
import com.app.dto.CustomerStatementDTO;
import com.app.entity.City;
import com.app.entity.Customer;
import com.app.entity.CustomerLedger;
import com.app.entity.CustomerLedger.TransactionType;
import com.app.entity.Driver;
import com.app.entity.Route;
import com.app.entity.Sale;
import com.app.repository.CustomerLedgerRepository;
import com.app.repository.CustomerRepository;
import com.app.repository.SaleRepository;
import com.app.exception.ResourceNotFoundException;

/**
 * The statement is what the ledger screen and the downloaded PDF are both built
 * from, so the figures it carries have to be right at the source rather than
 * recomputed on either side.
 *
 * What is asserted here is specifically what the plain ledger endpoint could not
 * do: state the balance brought forward into a filtered period, resolve each
 * sale's birds, weight and rate out of the referenced sale, and close on the
 * ledger's own running balance rather than on opening plus movement.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerStatementTest {

    @Mock
    private CustomerLedgerRepository ledgerRepository;

    @Mock
    private CustomerRepository customerRepository;

    @Mock
    private SaleRepository saleRepository;

    @InjectMocks
    private LedgerServiceImpl ledgerService;

    private Customer customer;

    @BeforeEach
    void setUp() {
        City city = new City();
        city.setName("Madha");

        customer = new Customer();
        customer.setId(42L);
        customer.setName("Imran Poultry");
        customer.setShopName("Imran Chicken Centre");
        customer.setMobileNo("9876543210");
        customer.setCity(city);
        customer.setCreditLimitEnabled(true);
        customer.setCreditLimit(new BigDecimal("150000.00"));

        when(customerRepository.findById(42L)).thenReturn(Optional.of(customer));
    }

    private CustomerLedger ledgerRow(long id, String date, TransactionType type,
                                     String debit, String credit, String balance, Long referenceId) {
        CustomerLedger row = new CustomerLedger();
        row.setId(id);
        row.setCustomer(customer);
        row.setTransactionDate(LocalDate.parse(date));
        row.setTransactionType(type);
        row.setReferenceType(type.name());
        row.setReferenceId(referenceId);
        row.setDebitAmount(new BigDecimal(debit));
        row.setCreditAmount(new BigDecimal(credit));
        row.setRunningBalance(new BigDecimal(balance));
        return row;
    }

    private Sale sale(long id, int birds, String kilograms, String rate) {
        Route route = new Route();
        route.setName("Kurduwadi");
        Driver driver = new Driver();
        driver.setName("Rashid");

        Sale sale = new Sale();
        sale.setId(id);
        sale.setBirds(birds);
        sale.setKilograms(new BigDecimal(kilograms));
        sale.setRate(new BigDecimal(rate));
        sale.setRoute(route);
        sale.setDriver(driver);
        sale.setVehicleNo(1234L);
        return sale;
    }

    @Test
    @DisplayName("a filtered statement opens on the balance carried in from before the period")
    void openingBalanceComesFromTheRowBeforeTheRange() {
        CustomerLedger before = ledgerRow(7, "2026-08-28", TransactionType.SALE,
                "5000.00", "0.00", "12000.00", 900L);

        when(ledgerRepository.findLatestBefore(eq(customer), eq(LocalDate.parse("2026-09-01")), any(Limit.class)))
                .thenReturn(List.of(before));
        when(ledgerRepository.findForStatement(eq(customer), any(), any()))
                .thenReturn(List.of(ledgerRow(8, "2026-09-02", TransactionType.PAYMENT,
                        "0.00", "2000.00", "10000.00", 55L)));

        CustomerStatementDTO statement = ledgerService.getCustomerStatement(
                42L, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));

        assertEquals(new BigDecimal("12000.00"), statement.getOpeningBalance());
        assertEquals(new BigDecimal("12000.00"), statement.getTotals().getOpeningBalance());
        assertEquals(new BigDecimal("10000.00"), statement.getTotals().getClosingBalance());
        assertEquals(new BigDecimal("2000.00"), statement.getTotals().getTotalCredit());
        assertEquals(1, statement.getTotals().getPaymentCount());
    }

    @Test
    @DisplayName("nothing before the period means the statement opens at zero, not at the first row")
    void openingBalanceIsZeroWhenNothingPrecedesThePeriod() {
        when(ledgerRepository.findLatestBefore(eq(customer), any(LocalDate.class), any(Limit.class)))
                .thenReturn(List.of());
        when(ledgerRepository.findForStatement(eq(customer), any(), any()))
                .thenReturn(List.of(ledgerRow(1, "2026-09-02", TransactionType.SALE,
                        "18500.00", "0.00", "18500.00", 1042L)));
        when(saleRepository.findAllById(anyList()))
                .thenReturn(List.of(sale(1042, 60, "121.500", "152.2500")));

        CustomerStatementDTO statement = ledgerService.getCustomerStatement(
                42L, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));

        assertEquals(new BigDecimal("0.00"), statement.getOpeningBalance());
    }

    @Test
    @DisplayName("a whole-history statement has no brought-forward figure to state")
    void wholeHistoryStatementOpensAtZeroWithoutLookingBack() {
        when(ledgerRepository.findAllForStatement(customer))
                .thenReturn(List.of(ledgerRow(1, "2026-09-02", TransactionType.OPENING_BALANCE,
                        "3000.00", "0.00", "3000.00", null)));

        CustomerStatementDTO statement = ledgerService.getCustomerStatement(42L, null, null);

        assertEquals(new BigDecimal("0.00"), statement.getOpeningBalance());
        assertNull(statement.getStartDate());
        // An opening-balance row is neither a sale, a payment nor an adjustment.
        assertEquals(0, statement.getTotals().getSaleCount());
        assertEquals(0, statement.getTotals().getAdjustmentCount());
        assertEquals(new BigDecimal("3000.00"), statement.getTotals().getClosingBalance());
    }

    @Test
    @DisplayName("each sale row carries the birds, weight, rate and trip from the sale it references")
    void saleRowsAreEnrichedFromTheReferencedSale() {
        when(ledgerRepository.findAllForStatement(customer)).thenReturn(List.of(
                ledgerRow(1, "2026-09-02", TransactionType.SALE, "18500.00", "5000.00", "13500.00", 1042L),
                ledgerRow(2, "2026-09-05", TransactionType.PAYMENT, "0.00", "3500.00", "10000.00", 77L)));
        when(saleRepository.findAllById(anyList()))
                .thenReturn(List.of(sale(1042, 60, "121.500", "152.2500")));

        CustomerStatementDTO statement = ledgerService.getCustomerStatement(42L, null, null);
        CustomerLedgerDTO saleRow = statement.getEntries().get(0);
        CustomerLedgerDTO paymentRow = statement.getEntries().get(1);

        assertEquals(60, saleRow.getBirds());
        assertEquals(new BigDecimal("121.500"), saleRow.getWeight());
        assertEquals(new BigDecimal("152.2500"), saleRow.getRate());
        assertEquals("Kurduwadi", saleRow.getRouteName());
        assertEquals("Rashid", saleRow.getDriverName());
        assertEquals("1234", saleRow.getVehicleNo());

        // A payment has no sale behind it and must not borrow one.
        assertNull(paymentRow.getBirds());
        assertNull(paymentRow.getRouteName());
    }

    @Test
    @DisplayName("quantities and the realised rate are totalled over the sales in the period")
    void totalsCoverQuantitiesAsWellAsMoney() {
        when(ledgerRepository.findAllForStatement(customer)).thenReturn(List.of(
                ledgerRow(1, "2026-09-02", TransactionType.SALE, "18500.00", "0.00", "18500.00", 1042L),
                ledgerRow(2, "2026-09-03", TransactionType.SALE, "9000.00", "0.00", "27500.00", 1043L),
                ledgerRow(3, "2026-09-05", TransactionType.CREDIT_NOTE, "0.00", "500.00", "27000.00", null)));
        when(saleRepository.findAllById(anyList())).thenReturn(List.of(
                sale(1042, 60, "121.500", "152.2500"),
                sale(1043, 30, "60.000", "150.0000")));

        CustomerStatementDTO statement = ledgerService.getCustomerStatement(42L, null, null);

        assertEquals(90, statement.getTotals().getBirds());
        assertEquals(new BigDecimal("181.500"), statement.getTotals().getWeight());
        assertEquals(new BigDecimal("27500.00"), statement.getTotals().getTotalDebit());
        assertEquals(new BigDecimal("500.00"), statement.getTotals().getTotalCredit());
        assertEquals(new BigDecimal("27000.00"), statement.getTotals().getNetMovement());
        // 27,500 over 181.5 kg.
        assertEquals(new BigDecimal("151.52"), statement.getTotals().getAverageRate());
        assertEquals(2, statement.getTotals().getSaleCount());
        assertEquals(1, statement.getTotals().getAdjustmentCount());
        assertEquals(new BigDecimal("27000.00"), statement.getTotals().getClosingBalance());
    }

    @Test
    @DisplayName("an account with no transactions still states its position")
    void emptyStatementCarriesTheBroughtForwardBalanceAsTheClosingBalance() {
        when(ledgerRepository.findLatestBefore(eq(customer), any(LocalDate.class), any(Limit.class)))
                .thenReturn(List.of(ledgerRow(3, "2026-07-30", TransactionType.SALE,
                        "1000.00", "0.00", "8250.00", 500L)));
        when(ledgerRepository.findForStatement(eq(customer), any(), any())).thenReturn(List.of());

        CustomerStatementDTO statement = ledgerService.getCustomerStatement(
                42L, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"));

        assertTrue(statement.getEntries().isEmpty());
        assertEquals(new BigDecimal("8250.00"), statement.getOpeningBalance());
        assertEquals(new BigDecimal("8250.00"), statement.getTotals().getClosingBalance());
        assertNull(statement.getFirstTransactionDate());
    }

    @Test
    @DisplayName("the customer's identity travels with the statement")
    void statementCarriesTheIdentityBlock() {
        when(ledgerRepository.findAllForStatement(customer)).thenReturn(List.of());

        CustomerStatementDTO statement = ledgerService.getCustomerStatement(42L, null, null);

        assertEquals("Imran Poultry", statement.getCustomerName());
        assertEquals("Imran Chicken Centre", statement.getShopName());
        assertEquals("Madha", statement.getCityName());
        assertEquals("9876543210", statement.getMobileNo());
        assertTrue(statement.isCreditLimitEnabled());
        assertEquals(new BigDecimal("150000.00"), statement.getCreditLimit());
    }

    @Test
    @DisplayName("a reversed range and a missing customer are rejected, not returned empty")
    void badRequestsFail() {
        assertThrows(IllegalArgumentException.class, () -> ledgerService.getCustomerStatement(
                42L, LocalDate.parse("2026-09-30"), LocalDate.parse("2026-09-01")));

        when(customerRepository.findById(9999L)).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> ledgerService.getCustomerStatement(9999L, null, null));
    }
}
