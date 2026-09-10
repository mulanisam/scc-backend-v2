package com.app.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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

import com.app.dto.SaleLineDto;
import com.app.dto.SalesBulkEntryDto;
import com.app.entity.Customer;
import com.app.entity.Sale;
import com.app.entity.SaleDetails;
import com.app.repository.CustomerRepository;
import com.app.repository.DriverRepository;
import com.app.repository.RouteRepository;
import com.app.repository.SaleDetailsRepository;
import com.app.repository.SaleRepository;
import com.app.repository.VehicleRepository;

import jakarta.persistence.EntityManager;

/**
 * The credit limit has to bite on the bulk entry path.
 *
 * It was checked only in createSingleSale. Bulk entry is how every one of the
 * 56,099 recorded sales was entered, so the hard block the business asked for did
 * not apply anywhere it mattered.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BulkEntryCreditLimitTest {

    @Mock private SaleRepository saleRepository;
    @Mock private CustomerRepository customerRepository;
    @Mock private SaleDetailsRepository saleDetailsRepository;
    @Mock private SendSmsService sendSmsService;
    @Mock private LedgerService ledgerService;
    @Mock private RouteRepository routeRepository;
    @Mock private VehicleRepository vehicleRepository;
    @Mock private DriverRepository driverRepository;
    @Mock private EntityManager entityManager;

    @InjectMocks
    private SalesServiceImpl salesService;

    @BeforeEach
    void setUp() {
        when(saleDetailsRepository.findByDateAndRouteAndVehicleAndDriver(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(saleDetailsRepository.save(any(SaleDetails.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(saleRepository.saveAll(anyIterable()))
                .thenAnswer(invocation -> new ArrayList<Sale>(invocation.getArgument(0)));
    }

    /** 100 kg at 100.00 is 10,000, of which `paid` is collected. */
    private SaleLineDto line(long customerId, int birds, String kilograms, String rate, String paid) {
        SaleLineDto line = new SaleLineDto();
        line.setCustomerId(customerId);
        line.setBirds(birds);
        line.setKilograms(new BigDecimal(kilograms));
        line.setRate(new BigDecimal(rate));
        line.setPayment(new BigDecimal(paid));
        line.setPaymentMode("cash");
        return line;
    }

    private SalesBulkEntryDto entry(List<SaleLineDto> lines) {
        int birds = lines.stream().mapToInt(SaleLineDto::getBirds).sum();

        SalesBulkEntryDto dto = new SalesBulkEntryDto();
        dto.setDate(LocalDate.now().minusDays(1));
        dto.setVehicleNo(1L);
        dto.setRoute(2L);
        dto.setDriver(3L);
        dto.setSalesDetails(lines);
        // Every bird loaded is sold, so the reconciliation passes.
        dto.setTotalBirds(birds);
        dto.setTotalBirdSale(birds);
        dto.setMortality(0);
        dto.setReturnToFarm(0);
        dto.setTotalAmount(BigDecimal.ZERO);
        dto.setTotalKilogramSale(BigDecimal.ZERO);
        dto.setTotalPaymentReceived(BigDecimal.ZERO);
        dto.setTotalPending(BigDecimal.ZERO);
        return dto;
    }

    private Customer customer(long id, String name, String limit) {
        Customer customer = new Customer();
        customer.setId(id);
        customer.setName(name);
        if (limit == null) {
            customer.setCreditLimitEnabled(false);
        } else {
            customer.setCreditLimitEnabled(true);
            customer.setCreditLimit(new BigDecimal(limit));
        }
        return customer;
    }

    @Test
    @DisplayName("an entry that puts a customer past their limit is refused, and nothing is written")
    void breachIsRefusedBeforeAnythingIsSaved() {
        Customer imran = customer(42L, "Imran Poultry", "15000.00");
        when(customerRepository.findAllById(anyIterable())).thenReturn(List.of(imran));
        when(ledgerService.isCreditLimitExceeded(eq(imran), any(BigDecimal.class))).thenReturn(true);
        when(ledgerService.getCurrentBalance(imran)).thenReturn(new BigDecimal("12000.00"));

        // 10,000 billed, nothing collected.
        SalesBulkEntryDto dto = entry(List.of(line(42L, 50, "100.000", "100.0000", "0")));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> salesService.salesBulkEntry(dto));

        assertTrue(thrown.getMessage().contains("Imran Poultry"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("15000.00"), thrown.getMessage());

        // Refused before the rows and before the ledger.
        verify(saleRepository, never()).saveAll(anyIterable());
        verify(ledgerService, never()).createSaleLedgerEntry(any(Sale.class));
    }

    @Test
    @DisplayName("two lines for one customer are summed before the limit is tested")
    void linesForTheSameCustomerAreSummed() {
        Customer imran = customer(42L, "Imran Poultry", "15000.00");
        when(customerRepository.findAllById(anyIterable())).thenReturn(List.of(imran));
        when(ledgerService.getCurrentBalance(imran)).thenReturn(BigDecimal.ZERO);
        // Only a request for the full 20,000 breaches; either line alone does not.
        when(ledgerService.isCreditLimitExceeded(eq(imran), eq(new BigDecimal("20000.00")))).thenReturn(true);

        // The same customer twice on one trip - it has happened on 175 trips here.
        SalesBulkEntryDto dto = entry(List.of(
                line(42L, 30, "100.000", "100.0000", "0"),
                line(42L, 20, "100.000", "100.0000", "0")));

        assertThrows(IllegalStateException.class, () -> salesService.salesBulkEntry(dto));
        verify(saleRepository, never()).saveAll(anyIterable());
    }

    @Test
    @DisplayName("a customer with no limit set is never blocked and costs no balance lookup")
    void customersWithoutALimitPassThrough() {
        Customer walkIn = customer(43L, "Walk-in trader", null);
        when(customerRepository.findAllById(anyIterable())).thenReturn(List.of(walkIn));

        SalesBulkEntryDto dto = entry(List.of(line(43L, 40, "80.000", "100.0000", "0")));

        List<Sale> saved = salesService.salesBulkEntry(dto);

        assertEquals(1, saved.size());
        verify(ledgerService, never()).isCreditLimitExceeded(any(Customer.class), any(BigDecimal.class));
        verify(ledgerService).createSaleLedgerEntry(any(Sale.class));
    }

    @Test
    @DisplayName("a fully paid line asks for no credit, so no limit applies")
    void fullyPaidLinesSkipTheCheckEntirely() {
        SalesBulkEntryDto dto = entry(List.of(line(42L, 50, "100.000", "100.0000", "10000")));

        salesService.salesBulkEntry(dto);

        // Nothing outstanding means no customer needed looking up at all.
        verify(customerRepository, never()).findAllById(anyIterable());
        verify(ledgerService, never()).isCreditLimitExceeded(any(Customer.class), any(BigDecimal.class));
    }

    @Test
    @DisplayName("the customer balance is left to the ledger, not incremented separately")
    void balanceIsMaintainedOnlyByTheLedger() {
        Customer walkIn = customer(43L, "Walk-in trader", null);
        when(customerRepository.findAllById(anyIterable())).thenReturn(List.of(walkIn));

        salesService.salesBulkEntry(entry(List.of(line(43L, 40, "80.000", "100.0000", "0"))));

        // updateBalanceAmount used to run here as well as the ledger's own write,
        // giving one column two writers whose outcome depended on flush order.
        verify(customerRepository, never()).updateBalanceAmount(any(Long.class), any(BigDecimal.class));
        verify(ledgerService).createSaleLedgerEntry(any(Sale.class));
    }
}
