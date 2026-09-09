package com.app.service;

import com.app.dto.TradingEntryDto;
import com.app.entity.TradingEntry;

public interface TradingService {
    TradingEntry createTradingEntry(TradingEntryDto tradingEntryDto);

    Integer getBalanceAmount(Long partyId, Long vendorId);
}
