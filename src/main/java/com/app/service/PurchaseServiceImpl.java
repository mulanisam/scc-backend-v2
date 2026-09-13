package com.app.service;

import java.math.BigDecimal;
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
import com.app.exception.ResourceNotFoundException;
import com.app.utility.MoneyRules;
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

    /** What makes the payable accumulate instead of being overwritten. */
    @Autowired
    private SupplierLedgerService supplierLedgerService;

    @Value("${file.upload-dir}")
    private String uploadDir;

    private static final Logger logger = LoggerFactory.getLogger(PurchaseServiceImpl.class);

    /**
     * Records a purchase and bills it to the supplier's account.
     *
     * Held to the same rules as a sale, which it was not before:
     *
     * <ul>
     *   <li>the line amount is recalculated from weight x rate and a disagreement is refused,
     *       rather than whatever the browser submitted being stored</li>
     *   <li>the date cannot be in the future</li>
     *   <li>a missing supplier, vehicle or driver is a 404 naming it, not a bare
     *       RuntimeException reported as a 500</li>
     *   <li>the amount owed goes on a ledger, so it accumulates</li>
     * </ul>
     *
     * <p>That last one is the substantive change. This method used to finish with
     * {@code supplier.setPendingPayment(totalAmount)}, which overwrote what the supplier was
     * owed with the total of the one new purchase - so five purchases worth 8,12,500 left a
     * payable of 1,95,000 on record, and 12,05,020 of what the business owed appeared nowhere.
     */
    public Purchase createPurchase(PurchaseDTO purchaseDTO, List<MultipartFile> files) {
        logger.info("Creating a purchase for supplier {} dated {}",
                purchaseDTO.getSupplier(), purchaseDTO.getEntryDate());

        if (purchaseDTO.getDcDetails() == null || purchaseDTO.getDcDetails().isEmpty()) {
            throw new IllegalArgumentException("A purchase needs at least one DC line.");
        }

        Purchase purchase = new Purchase();
        BeanUtils.copyProperties(purchaseDTO, purchase);

        // Parsed here rather than left to BeanUtils, which silently leaves entryDate null when
        // the DTO carries a String and the entity a LocalDate - and a purchase with no date
        // cannot be placed on a ledger or found by a payment.
        purchase.setEntryDate(parseEntryDate(purchaseDTO.getEntryDate()));
        if (purchase.getEntryDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Purchase date " + purchase.getEntryDate()
                    + " is in the future. Future-dated purchases cannot be saved.");
        }

        Vehicle vehicle = vehicleRepository.findById(purchaseDTO.getVehicle())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Vehicle " + purchaseDTO.getVehicle() + " was not found."));
        Driver driver = driverRepository.findById(purchaseDTO.getDriver())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Driver " + purchaseDTO.getDriver() + " was not found."));
        Supplier supplier = supplierRepository.findById(purchaseDTO.getSupplier())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Supplier " + purchaseDTO.getSupplier() + " was not found."));

        purchase.setVehicle(vehicle);
        purchase.setDriver(driver);
        purchase.setSupplier(supplier);

        BigDecimal totalAmount = BigDecimal.ZERO;
        for (int i = 0; i < purchaseDTO.getDcDetails().size(); i++) {
            DcDetailDTO dcDetailDTO = purchaseDTO.getDcDetails().get(i);
            DcDetail dcDetail = new DcDetail();
            BeanUtils.copyProperties(dcDetailDTO, dcDetail);
            dcDetail.setPurchase(purchase);

            // Calculated, not trusted - the same rule bulk sales, single sales and trading
            // entries all apply. A browser rounding differently from the server would
            // otherwise write a figure the payable disagrees with.
            BigDecimal lineAmount = amountFor(dcDetailDTO, i + 1);
            dcDetail.setAmount(lineAmount);
            totalAmount = totalAmount.add(lineAmount);

            if (i < files.size() && files.get(i) != null && !files.get(i).isEmpty()) {
                MultipartFile file = files.get(i);
                try {
                    String filePath = saveFile(file, dcDetailDTO.getDcNo(), supplier.getId().toString());
                    dcDetail.setFilePath(filePath);
                } catch (java.io.IOException e) {
                    // The DC scan is supporting evidence; losing it must not lose the purchase.
                    logger.error("Failed to save file for DC No: {}", dcDetailDTO.getDcNo(), e);
                }
            }

            purchase.getDcDetails().add(dcDetail);
        }

        purchase.setTotalAmount(MoneyRules.money(totalAmount));
        purchase.setPaidAmount(MoneyRules.money(BigDecimal.ZERO));

        Purchase savedPurchase = purchaseRepository.save(purchase);

        /*
         * The ledger is what makes the payable move, and it accumulates.
         *
         * What this replaces overwrote supplier.pending_payment with this purchase's total.
         * The service keeps that column in step as a convenience for older screens, but the
         * balance now comes from the rows.
         */
        supplierLedgerService.recordPurchase(savedPurchase);

        logger.info("Purchase {} recorded for {}: {} over {} line(s)",
                savedPurchase.getId(), supplier.getName(), savedPurchase.getTotalAmount(),
                savedPurchase.getDcDetails().size());
        return savedPurchase;
    }

    /**
     * The amount for one DC line, from its own weight and rate.
     *
     * Refuses a submitted figure that disagrees by more than the rounding rule allows, naming
     * the line - "DC line 2" - because a purchase with four lines and a silent correction is
     * worse than one that will not save.
     */
    private BigDecimal amountFor(DcDetailDTO line, int position) {
        BigDecimal calculated = MoneyRules.calculateAmount(line.getKilograms(), line.getRate());

        if (line.getAmount() != null && !MoneyRules.amountsMatch(calculated, line.getAmount())) {
            throw new IllegalArgumentException(String.format(
                    "DC line %d does not add up: %s kg at %s per kg is %s, but %s was submitted.",
                    position, line.getKilograms(), line.getRate(),
                    calculated.toPlainString(), MoneyRules.money(line.getAmount()).toPlainString()));
        }
        return calculated;
    }

    /** The DTO carries the date as text, because the form posts JSON inside a multipart field. */
    private static LocalDate parseEntryDate(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("A purchase must have a date.");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("\"" + value
                    + "\" is not a date. Use the form yyyy-MM-dd.");
        }
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

        // Passes the parsed date, which this method already computed and then
        // discarded in favour of the raw string.
        Optional<Purchase> purchaseEntry = purchaseRepository.findBySupplierIdAndEntryDate(supplierId, date);
        
        // Handle the case where purchaseEntry is not present
        if (purchaseEntry.isPresent()) {
            Optional<SupplierPaymentHist> purchaseHist = payHistRepository.findTopByPurchaseOrderByIdDesc(purchaseEntry.get());

            // If purchaseHist is present, return its pending payment and 0.0, else return the purchaseEntry's total and paid amounts
            if (purchaseHist.isPresent()) {
                SupplierPaymentHist histEntry = purchaseHist.get();
                return new PurchaseDetailsDTO(MoneyRules.money(histEntry.getPendingPayment()), MoneyRules.money(BigDecimal.ZERO));
            } else {
                Purchase entry = purchaseEntry.get();
                return new PurchaseDetailsDTO(entry.getTotalAmount(), entry.getPaidAmount());
            }
        } else {
            // If both purchaseEntry and purchaseHist are not present, return DTO with default values
            return new PurchaseDetailsDTO(MoneyRules.money(BigDecimal.ZERO), MoneyRules.money(BigDecimal.ZERO));
        }
    }

	@Override
	/**
	 * Records money paid to a supplier, against one purchase and on their ledger.
	 *
	 * Three things were wrong here and all three mattered.
	 *
	 * <p><b>It could not find a purchase when two shared a date.</b>
	 * findBySupplierIdAndEntryDate returns an Optional, so with the two Komarla Agrovet
	 * purchases both dated 2025-09-06 Spring Data throws before anything is saved - paying
	 * that supplier for that day was impossible. The purchase id is now accepted directly,
	 * and looking up by date is the fallback that says plainly when it is ambiguous.
	 *
	 * <p><b>purchase.paid_amount was never updated.</b> It was written as 0.00 on creation and
	 * left there, so the one payment on record - 50,000 against purchase 2 - left the purchase
	 * looking entirely unpaid.
	 *
	 * <p><b>The payable moved by a raw column update</b> that the purchase path then
	 * overwrote. It goes on the ledger now, which is what the balance is derived from.
	 */
	public SupplierPaymentHist savePurchasePayment(PurchasePaymentDTO purchasePaymentDto) {
		logger.info("Recording a supplier payment of {} for supplier {}",
				purchasePaymentDto.getPaidAmount(), purchasePaymentDto.getSupplier());

		BigDecimal paid = MoneyRules.money(purchasePaymentDto.getPaidAmount());
		if (paid.signum() <= 0) {
			throw new IllegalArgumentException("A payment must be more than zero.");
		}

		Supplier supplier = supplierRepository.findById(purchasePaymentDto.getSupplier())
				.orElseThrow(() -> new ResourceNotFoundException(
						"Supplier " + purchasePaymentDto.getSupplier() + " was not found."));

		Purchase purchaseEntry = findPurchaseToSettle(purchasePaymentDto);

		LocalDate transactionDate = purchasePaymentDto.getDateOfTransaction() == null
				? LocalDate.now()
				: purchasePaymentDto.getDateOfTransaction();
		if (transactionDate.isAfter(LocalDate.now())) {
			throw new IllegalArgumentException("Payment date " + transactionDate
					+ " is in the future. Future-dated payments cannot be saved.");
		}

		SupplierPaymentHist paymentHist = new SupplierPaymentHist();
		paymentHist.setDateOfPurchase(purchaseEntry.getEntryDate());
		paymentHist.setComment(purchasePaymentDto.getComment());
		paymentHist.setDateOfTransaction(transactionDate);
		paymentHist.setPaidAmount(paid);
		paymentHist.setTotalAmount(MoneyRules.money(purchaseEntry.getTotalAmount()));
		paymentHist.setTrans_id(purchasePaymentDto.getTrans_id());
		paymentHist.setSupplier(supplier);
		paymentHist.setPurchase(purchaseEntry);

		/*
		 * What remains on this purchase after this payment, carried forward from the last one
		 * rather than taken from the request - the browser's figure is a display value and
		 * would let two payments entered at once both claim the same opening amount.
		 */
		BigDecimal alreadyPaid = MoneyRules.money(purchaseEntry.getPaidAmount());
		BigDecimal outstanding = MoneyRules.money(purchaseEntry.getTotalAmount()).subtract(alreadyPaid);
		paymentHist.setPendingPayment(MoneyRules.money(outstanding.subtract(paid)));

		if (paid.compareTo(outstanding) > 0) {
			// Allowed, and logged: an advance or a rounded settlement is a real thing, but a
			// payment larger than the bill is worth being able to find afterwards.
			logger.warn("Payment of {} exceeds the {} outstanding on purchase {}",
					paid, outstanding, purchaseEntry.getId());
		}

		SupplierPaymentHist paymentHistSaved = payHistRepository.save(paymentHist);

		// Kept in step, so a purchase can say how much of it has been settled.
		purchaseEntry.setPaidAmount(MoneyRules.money(alreadyPaid.add(paid)));
		purchaseRepository.save(purchaseEntry);

		supplierLedgerService.recordPayment(paymentHistSaved);

		logger.info("Supplier payment {} recorded: {} against purchase {}, {} still outstanding",
				paymentHistSaved.getId(), paid, purchaseEntry.getId(),
				paymentHist.getPendingPayment());
		return paymentHistSaved;
	}

	/**
	 * The purchase a payment settles.
	 *
	 * By id when the caller knows it, which is what a screen listing a supplier's unpaid
	 * purchases can pass. By supplier and date otherwise, for the older form - and when that
	 * matches more than one purchase it says so rather than failing with Spring Data's
	 * "query did not return a unique result", which is what the two same-day Komarla Agrovet
	 * purchases produced.
	 */
	private Purchase findPurchaseToSettle(PurchasePaymentDTO dto) {
		if (dto.getPurchaseId() != null) {
			Purchase purchase = purchaseRepository.findById(dto.getPurchaseId())
					.orElseThrow(() -> new ResourceNotFoundException(
							"Purchase " + dto.getPurchaseId() + " was not found."));
			if (purchase.getSupplier() == null
					|| !purchase.getSupplier().getId().equals(dto.getSupplier())) {
				throw new IllegalArgumentException("Purchase " + dto.getPurchaseId()
						+ " does not belong to that supplier.");
			}
			return purchase;
		}

		if (dto.getDateOfPurchase() == null) {
			throw new IllegalArgumentException(
					"A payment must say which purchase it settles - by id, or by purchase date.");
		}

		List<Purchase> matches = purchaseRepository
				.findAllBySupplierIdAndEntryDate(dto.getSupplier(), dto.getDateOfPurchase());

		if (matches.isEmpty()) {
			throw new ResourceNotFoundException("No purchase from that supplier on "
					+ dto.getDateOfPurchase() + ".");
		}
		if (matches.size() > 1) {
			throw new IllegalArgumentException(matches.size() + " purchases from that supplier are"
					+ " dated " + dto.getDateOfPurchase()
					+ ", so it is not clear which this payment settles. Choose one by id ("
					+ matches.stream().map(p -> String.valueOf(p.getId())).collect(java.util.stream.Collectors.joining(", "))
					+ ").");
		}
		return matches.get(0);
	}
}
