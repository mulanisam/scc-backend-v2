package com.app.dto.trading;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.app.entity.TradingEntry;

/**
 * One trading entry as a screen needs it.
 *
 * A projection, not the entity. TradingEntry holds four lazy associations - party,
 * customer, trip and supplier - and returning it made Jackson touch an uninitialised
 * proxy the moment the transaction had closed: "Type definition error: [simple type,
 * class org.hibernate.proxy.pojo.bytebuddy.ByteBuddyInterceptor]", a 500 on an entry that
 * had in fact saved correctly. Serialising them eagerly instead would have sent the
 * party's whole vehicle list and the customer's balance and consent flags with every row.
 */
public record TradingEntryView(
        Long id,
        LocalDate date,
        Long partyId,
        String partyName,
        /** The ledger account the entry bills to. */
        Long customerId,
        Long tripId,
        Long supplierId,
        String supplierName,
        String vehicleNumber,
        Integer birds,
        BigDecimal kilograms,
        BigDecimal rate,
        BigDecimal amount,
        BigDecimal payment,
        BigDecimal pending,
        String paymentMode,
        /** Running balance after this entry, as the ledger recorded it. */
        BigDecimal balanceAmount,
        String description,
        boolean obsolete) {

    public static TradingEntryView of(TradingEntry entry) {
        return new TradingEntryView(
                entry.getId(),
                entry.getDate(),
                entry.getParty() == null ? null : entry.getParty().getId(),
                entry.getParty() == null ? null : entry.getParty().getName(),
                entry.getCustomer() == null ? null : entry.getCustomer().getId(),
                entry.getTrip() == null ? null : entry.getTrip().getId(),
                entry.getSupplier() == null ? null : entry.getSupplier().getId(),
                entry.getSupplier() == null ? null : entry.getSupplier().getName(),
                entry.getVehicleNumber(),
                entry.getBirdsSold() == null ? entry.getBirds() : entry.getBirdsSold(),
                entry.getKilograms(),
                entry.getRate(),
                entry.getAmount(),
                entry.getPayment(),
                entry.getPending(),
                entry.getPaymentMode(),
                entry.getBalanceAmount(),
                entry.getDescription(),
                entry.isObsolete());
    }
}
