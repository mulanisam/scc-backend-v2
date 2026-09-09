package com.app.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.Month;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.app.dto.DcDetailDTO;
import com.app.dto.PurchaseDTO;
import com.app.dto.PurchaseDetailsDTO;
import com.app.dto.PurchasePaymentDTO;
import com.app.entity.DcDetail;
import com.app.entity.Driver;
import com.app.entity.Purchase;
import com.app.entity.Supplier;
import com.app.entity.SupplierPaymentHist;
import com.app.entity.Vehicle;
import com.app.repository.DriverRepository;
import com.app.repository.PurchaseRepository;
import com.app.repository.SupplierPayHistRepository;
import com.app.repository.SupplierRepository;
import com.app.repository.VehicleRepository;

import jakarta.persistence.EntityNotFoundException;


@Service
@Transactional
public class PurchaseServiceImpl implements PurchaseService {

    @Autowired
    private PurchaseRepository purchaseRepository;

    @Autowired
    private VehicleRepository vehicleRepository;

    @Autowired
    private DriverRepository driverRepository;

    @Autowired
    private SupplierRepository supplierRepository;
    
    @Autowired
    private SupplierPayHistRepository payHistRepository;

    @Value("${file.upload-dir}")
    private String uploadDir;

    private static final Logger logger = LoggerFactory.getLogger(PurchaseServiceImpl.class);

    public Purchase createPurchase(PurchaseDTO purchaseDTO, List<MultipartFile> files) {
        logger.info("Creating purchase with DTO: {}", purchaseDTO);
        Purchase purchase = new Purchase();
        BeanUtils.copyProperties(purchaseDTO, purchase);

        Vehicle vehicle = vehicleRepository.findById(purchaseDTO.getVehicle())
                .orElseThrow(() -> new RuntimeException("Vehicle not found"));
        Driver driver = driverRepository.findById(purchaseDTO.getDriver())
                .orElseThrow(() -> new RuntimeException("Driver not found"));
        Supplier supplier = supplierRepository.findById(purchaseDTO.getSupplier())
                .orElseThrow(() -> new RuntimeException("Supplier not found"));

        supplier.setPendingPayment(null);
        purchase.setVehicle(vehicle);
        purchase.setDriver(driver);
        purchase.setSupplier(supplier);
       
        double totalAmount=0;
        for (int i = 0; i < purchaseDTO.getDcDetails().size(); i++) {
            DcDetailDTO dcDetailDTO = purchaseDTO.getDcDetails().get(i);
            DcDetail dcDetail = new DcDetail();
            BeanUtils.copyProperties(dcDetailDTO, dcDetail);
            dcDetail.setPurchase(purchase);
            totalAmount=totalAmount+dcDetailDTO.getAmount();
            if (files.get(i) != null && !files.get(i).isEmpty()) {
                MultipartFile file = files.get(i);
                try {
                    String filePath = saveFile(file, dcDetailDTO.getDcNo(), supplier.getId().toString());
                    dcDetail.setFilePath(filePath);
                } catch ( java.io.IOException e) {
                    logger.error("Failed to save file for DC No: {}", dcDetailDTO.getDcNo(), e);
                }
            }

            purchase.getDcDetails().add(dcDetail);
        }
        purchase.setTotalAmount(totalAmount);
        purchase.setPaidAmount(0.0);
        Purchase savedPurchase = purchaseRepository.save(purchase);
        logger.info("Purchase created successfully with ID: {}", savedPurchase.getId());
        
        purchaseRepository.updatePendingAmount(purchaseDTO.getSupplier(), totalAmount);
		logger.info("Pending amount updated in Hist for Supplier Id : {} ", totalAmount);
		
		supplier.setPendingPayment(totalAmount);
		supplierRepository.save(supplier);
		logger.info("Pending amount updated for Supplier Id : {} ", totalAmount);
        
        return savedPurchase;
    }

    private String saveFile(MultipartFile file, String dcNo, String supplierId) throws java.io.IOException {
        logger.info("Saving file for DC No: {} and Supplier ID: {}", dcNo, supplierId);
        String currentYear = String.valueOf(Year.now().getValue());
        String currentMonth = Month.of(LocalDate.now().getMonthValue()).name();
        String datePart = LocalDate.now().format(DateTimeFormatter.ofPattern("ddMMyyyy"));
        String fileExtension = file.getOriginalFilename() != null ? file.getOriginalFilename().substring(file.getOriginalFilename().lastIndexOf('.')) : "";
        String fileName = String.format("DC-%s-%s-%s%s", dcNo, supplierId, datePart, fileExtension);

        Path fileStorageLocation = Paths.get(uploadDir).resolve(currentYear).resolve(currentMonth);
        Files.createDirectories(fileStorageLocation);

        Path targetLocation = fileStorageLocation.resolve(fileName);
        Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

        logger.info("File saved at: {}", targetLocation.toString());
        return targetLocation.toString();
    }
    
    @Override
    public PurchaseDetailsDTO getPurchaseDetails(Long supplierId, String entryDate) {
        logger.info("getPurchaseDetails supplierId: {}, entryDate: {}", supplierId, entryDate);
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate date = LocalDate.parse(entryDate, formatter);

        Optional<Purchase> purchaseEntry = purchaseRepository.findBySupplierIdAndEntryDate(supplierId, entryDate);      
        
        // Handle the case where purchaseEntry is not present
        if (purchaseEntry.isPresent()) {
            Optional<SupplierPaymentHist> purchaseHist = payHistRepository.findTopByPurchaseOrderByIdDesc(purchaseEntry.get());

            // If purchaseHist is present, return its pending payment and 0.0, else return the purchaseEntry's total and paid amounts
            if (purchaseHist.isPresent()) {
                SupplierPaymentHist histEntry = purchaseHist.get();
                return new PurchaseDetailsDTO(histEntry.getPendingPayment(), 0.0);
            } else {
                Purchase entry = purchaseEntry.get();
                return new PurchaseDetailsDTO(entry.getTotalAmount(), entry.getPaidAmount());
            }
        } else {
            // If both purchaseEntry and purchaseHist are not present, return DTO with default values
            return new PurchaseDetailsDTO(0.0, 0.0);
        }
    }

	@Override
	public SupplierPaymentHist savePurchasePayment(PurchasePaymentDTO purchasePaymentDto) {
		logger.info("Creating purchase payment with DTO: {}", purchasePaymentDto);
		SupplierPaymentHist paymentHist =  new SupplierPaymentHist();
		
		Supplier supplier = supplierRepository.findById(purchasePaymentDto.getSupplier())
                .orElseThrow(() -> new RuntimeException("Supplier not found"));
		
		Purchase purchaseEntry = purchaseRepository.findBySupplierIdAndEntryDate(purchasePaymentDto.getSupplier(), purchasePaymentDto.getDateOfPurchase().toString())
				 .orElseThrow(() -> new RuntimeException("Purchase Entry not found"));

		paymentHist.setDateOfPurchase(purchasePaymentDto.getDateOfPurchase());
		paymentHist.setComment(purchasePaymentDto.getComment());
		paymentHist.setDateOfTransaction(purchasePaymentDto.getDateOfTransaction());
		paymentHist.setPaidAmount(purchasePaymentDto.getPaidAmount());
		paymentHist.setTotalAmount(purchasePaymentDto.getTotalAmount());
		paymentHist.setTrans_id(purchasePaymentDto.getTrans_id());
		paymentHist.setSupplier(supplier);
		paymentHist.setPurchase(purchaseEntry);
		
		Purchase purchase = purchaseRepository.findById(purchaseEntry.getId()).orElseThrow(() -> new EntityNotFoundException("Purchase not found"));
		
		Optional<SupplierPaymentHist> purchaseHist =  payHistRepository.findTopByPurchaseOrderByIdDesc(purchase);
		if(purchaseHist.isPresent()) {
			paymentHist.setPendingPayment(purchaseHist.get().getPendingPayment()-purchasePaymentDto.getPaidAmount());
		}else
		{
			paymentHist.setPendingPayment(purchasePaymentDto.getPendingPayment());
		}
		SupplierPaymentHist paymentHistSaved = payHistRepository.save(paymentHist);
		logger.info("Purchase Payment created successfully with ID: {}", paymentHist.getId());
		
		purchaseRepository.updatePendingAmount(purchasePaymentDto.getSupplier(), (-purchasePaymentDto.getPaidAmount()));
		logger.info("Pending amount updated for Supplier Id : {} ", purchasePaymentDto.getPendingPayment());
		return paymentHistSaved;
	}
}
