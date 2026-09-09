package com.app.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.TradingEntryDto;
import com.app.entity.Party;
import com.app.entity.PartyVehicle;
import com.app.entity.Supplier;
import com.app.entity.TradingEntry;
import com.app.repository.PartyRepository;
import com.app.repository.PartyVehicleRepository;
import com.app.repository.SupplierRepository;
import com.app.repository.TradingEntryRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class TradingServiceImpl implements TradingService {

    private static final Logger logger = LoggerFactory.getLogger(TradingServiceImpl.class);

    private final TradingEntryRepository tradingEntryRepository;
    private final PartyRepository partyRepository;
    private final SupplierRepository supplierRepository;
    private final PartyVehicleRepository partyVehicleRepository;

    @Override
    @Transactional
    public TradingEntry createTradingEntry(TradingEntryDto dto) {
        logger.info("Creating trading entry with data: {}", dto);

        try {
            TradingEntry entry = new TradingEntry();
            entry.setDate(dto.getDate());
            entry.setBirds(dto.getBirds());
            entry.setKilograms(dto.getKilograms());
            entry.setRate(dto.getRate());
            entry.setAmount(dto.getAmount());
            entry.setPayment(dto.getPayment());
            entry.setPending(dto.getPending());
            entry.setBalanceAmount(dto.getBalanceAmount());
            entry.setDescription(dto.getDescription());

            Party party = partyRepository.findById(dto.getPartyId())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid Party ID"));
            Supplier supplier = supplierRepository.findById(dto.getSupplierId())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid Vendor ID"));
            PartyVehicle partyVehicle = partyVehicleRepository.findById(dto.getPartyVehicleId())
                    .orElseThrow(() -> new IllegalArgumentException("Invalid Vehicle ID"));

            entry.setParty(party);
            entry.setSupplier(supplier);
            entry.setPartyVehicle(partyVehicle);

            TradingEntry savedEntry = tradingEntryRepository.save(entry);

            logger.info("Trading entry saved successfully with id: {}", savedEntry.getId());
            return savedEntry;

        } catch (Exception e) {
            logger.error("Error creating trading entry", e);
            throw new RuntimeException("Failed to create trading entry: " + e.getMessage());
        }
    }

    @Override
    public Integer getBalanceAmount(Long partyId, Long vendorId) {
        logger.info("Fetching balance amount for partyId: {}, vendorId: {}", partyId, vendorId);
        try {
            // Placeholder: Implement your balance amount fetching logic here
            return 0;
        } catch (Exception e) {
            logger.error("Error fetching balance amount", e);
            throw new RuntimeException("Error fetching balance amount");
        }
    }
}
