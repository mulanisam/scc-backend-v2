package com.app.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import com.app.entity.Customer;
import com.app.entity.Driver;
import com.app.entity.Route;
import com.app.entity.Sale;
import com.app.entity.SaleDetails;
import com.app.utility.MoneyRules;

/**
 * Builds Sale rows from a bulk entry request.
 *
 * Amount and pending are calculated here from kilograms and rate using the
 * shared money rules, rather than taken from the request. The client's own
 * figure is verified separately by the service and used only as a checksum.
 */
public final class SaleMapper {

    private SaleMapper() {
    }

    public static List<Sale> mapToSales(List<SaleLineDto> salesDetails, LocalDate date, Long vehicleId,
            Long routeId, Long driverId, SaleDetails saleDetailData) {

        List<Sale> sales = new ArrayList<>();
        if (salesDetails == null) {
            return sales;
        }

        for (SaleLineDto line : salesDetails) {
            // Calculate from the values as submitted, exactly as the service's
            // validation does. Scaling the inputs first could produce a
            // different amount from the one validation just approved.
            BigDecimal amount = MoneyRules.calculateAmount(line.getKilograms(), line.getRate());
            BigDecimal payment = MoneyRules.money(line.getPayment());
            BigDecimal pending = MoneyRules.calculatePending(amount, payment);

            // Scaled only for storage.
            BigDecimal kilograms = MoneyRules.weight(line.getKilograms());
            BigDecimal rate = MoneyRules.money(line.getRate());

            Sale sale = new Sale();
            sale.setDate(date);
            sale.setVehicleNo(vehicleId);
            sale.setKilograms(kilograms.doubleValue());
            sale.setRate(rate.doubleValue());
            sale.setBirds(line.getBirds() == null ? 0 : line.getBirds());
            // These columns are still integral rupees; the money migration
            // widens them to DECIMAL, at which point the BigDecimal values
            // above can be stored directly.
            sale.setAmount(amount.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact());
            sale.setPayment(payment.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact());
            sale.setPending(pending.setScale(0, java.math.RoundingMode.HALF_UP).intValueExact());
            sale.setPaymentMode(line.getPaymentMode());
            sale.setDescription(line.getDescription());

            Route route = new Route();
            route.setId(routeId);
            sale.setRoute(route);

            Customer customer = new Customer();
            customer.setId(line.getCustomerId());
            sale.setCustomer(customer);

            Driver driver = new Driver();
            driver.setId(driverId);
            sale.setDriver(driver);

            sale.setSaleDetails(saleDetailData);
            sales.add(sale);
        }

        return sales;
    }
}
