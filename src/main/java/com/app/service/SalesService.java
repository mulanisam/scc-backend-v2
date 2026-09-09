package com.app.service;

import java.time.LocalDate;
import java.util.List;

import com.app.dto.SalesBulkEntryDto;
import com.app.dto.SingleSaleEntryDTO;
import com.app.entity.Sale;
import com.app.entity.SaleDetails;

public interface SalesService {

        public List<Sale> salesBulkEntry(SalesBulkEntryDto salesBulkEntryDto);
        
        /**
         * New: Single sale entry with automatic ledger creation
         */
        public Sale createSingleSale(SingleSaleEntryDTO saleDTO);

        public SaleDetails saveSaleDetails(SaleDetails saleDetails);
        public SaleDetails getSaleDetails(LocalDate date, String route, String vehicle, String driver);
}
