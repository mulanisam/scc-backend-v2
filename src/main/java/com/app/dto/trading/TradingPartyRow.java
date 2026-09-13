package com.app.dto.trading;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One wholesale party as the Trading party list shows it.
 *
 * The balance comes from the ledger account, not from a figure kept on the party. Those
 * two numbers exist and they are not the same thing: parties.balance_amount is a field
 * somebody types, while the customer's balance is maintained by the ledger on every entry
 * and payment. After route 9 moved, the second is the one carrying 44,03,490 - so it is
 * the one shown, and the party's own column is left where it is rather than being trusted.
 */
public record TradingPartyRow(
        Long partyId,
        String name,
        String owner,
        String city,
        String mobileNo,
        /** Null when the party has no ledger account and so cannot be billed. */
        Long customerId,
        BigDecimal balance,
        int vehicleCount,
        /** Entries in the period being looked at, not over all time. */
        long entryCount,
        long birds,
        BigDecimal kilograms,
        BigDecimal amount,
        BigDecimal received,
        LocalDate firstEntry,
        LocalDate lastEntry,
        boolean obsolete) {
}
