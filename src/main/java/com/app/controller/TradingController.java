package com.app.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.app.dto.TradingEntryDto;
import com.app.dto.TradingPaymentDto;
import com.app.dto.payment.PaymentView;
import com.app.dto.trading.TradingEntryView;
import com.app.entity.TradingEntry;
import com.app.service.TradingService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/trading")
@RequiredArgsConstructor
@Validated
public class TradingController {

    private static final Logger logger = LoggerFactory.getLogger(TradingController.class);

    private final TradingService tradingService;

    /**
     * Records a wholesale delivery to a party: posts it to the party's ledger and, if
     * asked, tells them about it by SMS or WhatsApp.
     *
     * Returns a view rather than the entity. The entity carries four lazy associations,
     * and serialising it made Jackson touch an uninitialised proxy after the transaction
     * closed - a 500 on an entry that had saved correctly, ledger and all.
     */
    @PostMapping
    public ResponseEntity<TradingEntryView> createTradingEntry(@RequestBody TradingEntryDto dto) {
        // The whole DTO used to be logged here, which put a party name, a rate and a
        // balance in the log file on every entry. The identifiers are enough to trace it.
        logger.info("Trading entry requested for party {} on {}", dto.getPartyId(), dto.getDate());
        TradingEntry createdEntry = tradingService.createTradingEntry(dto);
        return ResponseEntity.ok(TradingEntryView.of(createdEntry));
    }

    /**
     * Records money received from a party.
     *
     * New, and it should have existed from the start: the trading screen's payment tab has
     * always posted to /trading/payment, which no controller mapped. Every party payment
     * entered there returned 404 under a generic "Error creating payment entry" - the money
     * was taken, and the balance never moved.
     *
     * The receipt goes through the same service a retail payment does, so it lands in the
     * same table and on the same statement.
     */
    @PostMapping("/payment")
    public ResponseEntity<PaymentView> createPartyPayment(@RequestBody TradingPaymentDto dto) {
        logger.info("Party payment requested for party {} on {}", dto.getPartyId(), dto.getDate());
        return ResponseEntity.ok(tradingService.createPartyPayment(dto));
    }

    @GetMapping("/balanceAmount")
    public ResponseEntity<Integer> getBalanceAmount(@RequestParam Long partyId, @RequestParam Long vendorId) {
        logger.info("Request to get balance amount for partyId: {}, vendorId: {}", partyId, vendorId);
        Integer balanceAmount = tradingService.getBalanceAmount(partyId, vendorId);
        return ResponseEntity.ok(balanceAmount);
    }
}
