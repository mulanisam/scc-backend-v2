package com.app.service;

import com.app.dto.TradingEntryDto;
import com.app.dto.TradingPaymentDto;
import com.app.dto.payment.PaymentView;
import com.app.entity.TradingEntry;

public interface TradingService {
    TradingEntry createTradingEntry(TradingEntryDto tradingEntryDto);

    /**
     * Records money received from a party, against the party's ledger account.
     *
     * Stored as a CustomerPayment, because that is what it is: a party's account is a
     * customer ledger account, so the receipt is saved, reversed and reported by the same
     * code as a retail one rather than by a parallel set of it. A view comes back rather than
     * the entity - the customer behind a party is a lazy proxy, and serialising one is a 500
     * on a payment that saved correctly.
     */
    PaymentView createPartyPayment(TradingPaymentDto paymentDto);

    Integer getBalanceAmount(Long partyId, Long vendorId);
}
