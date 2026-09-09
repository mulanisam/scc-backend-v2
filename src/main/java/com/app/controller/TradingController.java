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

    @PostMapping
    public ResponseEntity<TradingEntry> createTradingEntry( @RequestBody TradingEntryDto dto) {
        logger.info("Request to create trading entry: {}", dto);
        TradingEntry createdEntry = tradingService.createTradingEntry(dto);
        return ResponseEntity.ok(createdEntry);
    }

    @GetMapping("/balanceAmount")
    public ResponseEntity<Integer> getBalanceAmount(@RequestParam Long partyId, @RequestParam Long vendorId) {
        logger.info("Request to get balance amount for partyId: {}, vendorId: {}", partyId, vendorId);
        Integer balanceAmount = tradingService.getBalanceAmount(partyId, vendorId);
        return ResponseEntity.ok(balanceAmount);
    }
}
