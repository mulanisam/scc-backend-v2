package com.app.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.app.dto.contact.ContactIssueRow;
import com.app.dto.contact.ContactQualityResponse;
import com.app.dto.contact.SharedNumberGroup;
import com.app.entity.Customer;
import com.app.exception.ResourceNotFoundException;
import com.app.repository.CustomerRepository;
import com.app.utility.MobileNumberRules;
import com.app.utility.MoneyRules;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

/**
 * Finds the customers who cannot be sent a message, and lets their number be
 * corrected one field at a time.
 *
 * Built because the WhatsApp plan sends ledger statements, and a statement carries
 * a balance: it can only go to a number that is valid and known to belong to one
 * customer. Neither holds today for 130 customers, and 11 numbers are shared.
 *
 * Classification is done in Java through MobileNumberRules rather than in SQL, so
 * there is one definition of "usable" - the send path will ask the same class the
 * same question, and a rule that lives in a query cannot be reused there.
 */
@Service
public class ContactQualityService {

    private static final Logger logger = LoggerFactory.getLogger(ContactQualityService.class);

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private CustomerRepository customerRepository;

    @Transactional(readOnly = true)
    public ContactQualityResponse getContactQuality() {
        logger.info("Assessing customer contact quality");

        // Trading history comes along for the ride so the list can be worked in
        // order of what it is worth fixing, rather than by customer id.
        @SuppressWarnings("unchecked")
        List<Object[]> rows = entityManager.createNativeQuery("""
                SELECT c.id,
                       c.name,
                       c.shop_name,
                       city.name,
                       c.mobile_no,
                       c.balance_amount,
                       c.obsolete,
                       (SELECT MAX(s.date) FROM sale s WHERE s.customer_id = c.id AND s.obsolete = 0),
                       (SELECT COUNT(*)    FROM sale s WHERE s.customer_id = c.id AND s.obsolete = 0)
                FROM customer c
                LEFT JOIN city city ON city.id = c.city_id
                ORDER BY c.balance_amount DESC
                """).getResultList();

        List<ContactIssueRow> unusable = new ArrayList<>();
        // Keyed on the normalised number so "98765 43210" and "9876543210" are
        // recognised as the same phone.
        Map<String, List<ContactIssueRow>> byNumber = new LinkedHashMap<>();

        long total = 0;
        long active = 0;
        BigDecimal unreachableBalance = BigDecimal.ZERO;

        for (Object[] row : rows) {
            total++;
            boolean obsolete = asBoolean(row[6]);
            if (!obsolete) {
                active++;
            }

            String recorded = (String) row[4];
            MobileNumberRules.Status status = MobileNumberRules.classify(recorded);

            ContactIssueRow issue = new ContactIssueRow();
            issue.setCustomerId(asLong(row[0]));
            issue.setCustomerName((String) row[1]);
            issue.setShopName((String) row[2]);
            issue.setCityName((String) row[3]);
            issue.setMobileNo(recorded == null ? "" : recorded.trim());
            issue.setBalance(MoneyRules.money(asDecimal(row[5])));
            issue.setLastSaleDate(row[7] == null ? null : asLocalDate(row[7]));
            issue.setSaleCount(asLong(row[8]));
            issue.setStatus(status.name());
            issue.setReason(MobileNumberRules.describe(status));

            if (status != MobileNumberRules.Status.VALID) {
                // An obsolete customer with nothing owed is not worth chasing, but
                // it is still counted - hiding it would make the totals disagree
                // with the customer list.
                unusable.add(issue);
                if (issue.getBalance().signum() > 0) {
                    unreachableBalance = unreachableBalance.add(issue.getBalance());
                }
                continue;
            }

            byNumber.computeIfAbsent(MobileNumberRules.normalise(recorded), key -> new ArrayList<>()).add(issue);
        }

        // Only numbers with more than one customer are a problem.
        List<SharedNumberGroup> shared = new ArrayList<>();
        long onShared = 0;
        long reachable = 0;
        BigDecimal sharedBalance = BigDecimal.ZERO;

        for (Map.Entry<String, List<ContactIssueRow>> entry : byNumber.entrySet()) {
            List<ContactIssueRow> customers = entry.getValue();
            if (customers.size() == 1) {
                reachable++;
                continue;
            }

            BigDecimal groupBalance = BigDecimal.ZERO;
            List<String> names = customers.stream().map(ContactIssueRow::getCustomerName).toList();
            for (ContactIssueRow customer : customers) {
                customer.setStatus("SHARED");
                customer.setReason("This number is recorded against " + customers.size() + " customers");
                // Each row names the others, so a single row read on its own still
                // says who else would receive this customer's statement.
                customer.setSharedWith(names.stream()
                        .filter(name -> !name.equals(customer.getCustomerName()))
                        .toList());
                groupBalance = groupBalance.add(customer.getBalance());
            }

            SharedNumberGroup group = new SharedNumberGroup();
            group.setMobileNo(entry.getKey());
            group.setCustomerCount(customers.size());
            group.setTotalBalance(MoneyRules.money(groupBalance));
            group.setCustomers(customers);

            boolean duplicate = hasRepeatedName(names);
            group.setLikelyDuplicateCustomer(duplicate);
            group.setSuggestion(duplicate
                    ? "These look like one customer entered twice, not two people sharing a phone. "
                      + "Their balance is split across the records, so merge them - giving one a "
                      + "different number would leave both statements wrong."
                    : "Confirm which customer owns this phone, and record the others' own numbers.");
            shared.add(group);

            onShared += customers.size();
            sharedBalance = sharedBalance.add(groupBalance);
        }

        // Most customers on a number first, then most money behind it.
        shared.sort(Comparator.comparingInt(SharedNumberGroup::getCustomerCount).reversed()
                .thenComparing(SharedNumberGroup::getTotalBalance, Comparator.reverseOrder()));

        // Highest balance first: 89 unreachable customers owe 5,29,260 between
        // them, and that is the order the work pays back in.
        unusable.sort(Comparator.comparing(ContactIssueRow::getBalance, Comparator.reverseOrder())
                .thenComparing(ContactIssueRow::getCustomerName, Comparator.nullsLast(String::compareTo)));

        ContactQualityResponse response = new ContactQualityResponse();
        response.setTotalCustomers(total);
        response.setActiveCustomers(active);
        response.setReachable(reachable);
        response.setUnusable(unusable.size());
        response.setOnSharedNumbers(onShared);
        response.setSharedNumberCount(shared.size());
        response.setUnreachableBalance(MoneyRules.money(unreachableBalance));
        response.setSharedNumberBalance(MoneyRules.money(sharedBalance));
        response.setUnusableNumbers(unusable);
        response.setSharedNumbers(shared);
        return response;
    }

    /**
     * Sets one customer's mobile number, and nothing else.
     *
     * Deliberately not the existing PUT /user/customers/{id}, which takes a whole
     * CustomerDTO: correcting a phone number through that endpoint means resending
     * every other field, and anything the caller leaves out is written back as
     * null. That is the same shape of bug as the stub-Customer merge that was
     * nulling city_id, and it has no place in a data-cleanup screen.
     */
    @Transactional
    public Customer updateMobileNumber(Long customerId, String mobileNo) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer " + customerId + " was not found."));

        String normalised = MobileNumberRules.normalise(mobileNo);
        MobileNumberRules.Status status = MobileNumberRules.classify(normalised);

        if (status != MobileNumberRules.Status.VALID) {
            throw new IllegalArgumentException("\"" + (mobileNo == null ? "" : mobileNo.trim())
                    + "\" cannot be used: " + MobileNumberRules.describe(status) + ".");
        }

        // Refuse a number already on another customer, rather than silently adding
        // to the shared-number problem this screen exists to clear up.
        List<Customer> existing = customerRepository.findByMobileNo(normalised);
        for (Customer other : existing) {
            if (!other.getId().equals(customerId)) {
                throw new IllegalStateException("That number is already recorded for "
                        + other.getName() + ". Two customers cannot share a number if either is to be"
                        + " sent a statement.");
            }
        }

        logger.info("Updating mobile number for customer {} ({})", customerId, customer.getName());
        customer.setMobileNo(normalised);
        return customerRepository.save(customer);
    }

    /**
     * Whether two of these names are the same person written differently.
     *
     * Compared on letters only, lower-cased, with spaces removed, and also on the
     * first word alone - which is what separates "SHAUKAT Nimgao" from "shaukat"
     * (a village suffix on one record) and catches "ABHIJIT JAMDADE" against
     * "Abhijit jamdade" (capitals only). Kept blunt on purpose: this raises a
     * suggestion for a human to confirm, it does not merge anything.
     */
    private boolean hasRepeatedName(List<String> names) {
        List<String> full = new ArrayList<>();
        List<String> firstWords = new ArrayList<>();

        for (String name : names) {
            if (name == null) {
                continue;
            }
            String letters = name.toLowerCase().replaceAll("[^a-z]", "");
            if (letters.isEmpty()) {
                continue;
            }
            if (full.contains(letters)) {
                return true;
            }
            full.add(letters);

            String first = name.trim().toLowerCase().split("\\s+")[0].replaceAll("[^a-z]", "");
            // Two-letter fragments match too loosely to mean anything.
            if (first.length() >= 4) {
                if (firstWords.contains(first)) {
                    return true;
                }
                firstWords.add(first);
            }
        }
        return false;
    }

    private static long asLong(Object value) {
        return value == null ? 0L : ((Number) value).longValue();
    }

    private static BigDecimal asDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal decimal) return decimal;
        return new BigDecimal(value.toString());
    }

    private static boolean asBoolean(Object value) {
        if (value == null) return false;
        if (value instanceof Boolean flag) return flag;
        return ((Number) value).intValue() != 0;
    }

    private static LocalDate asLocalDate(Object value) {
        if (value instanceof Date sqlDate) return sqlDate.toLocalDate();
        if (value instanceof LocalDate localDate) return localDate;
        return LocalDate.parse(value.toString());
    }
}
