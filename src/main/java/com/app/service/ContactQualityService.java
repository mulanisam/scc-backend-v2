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
                       (SELECT COUNT(*)    FROM sale s WHERE s.customer_id = c.id AND s.obsolete = 0),
                       -- Appended rather than placed beside mobile_no: the rows are read
                       -- by position, so inserting a column in the middle would silently
                       -- shift the balance into the obsolete flag.
                       c.alternate_mobile_no
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
            String alternate = (String) row[9];

            /*
             * Either number makes a customer reachable, and the primary wins.
             *
             * This has to agree with Customer.messagingNumber(), which is what the send
             * path actually uses. If this screen judged on the primary alone it would
             * list a customer as unreachable while the dispatcher happily messaged their
             * alternate - and the 130-number work list would never shrink, because
             * filling in a second number would not remove anybody from it.
             */
            MobileNumberRules.Status primaryStatus = MobileNumberRules.classify(recorded);
            MobileNumberRules.Status alternateStatus = MobileNumberRules.classify(alternate);

            boolean usingAlternate = primaryStatus != MobileNumberRules.Status.VALID
                    && alternateStatus == MobileNumberRules.Status.VALID;
            String effective = usingAlternate ? alternate : recorded;
            MobileNumberRules.Status status = usingAlternate ? alternateStatus : primaryStatus;

            ContactIssueRow issue = new ContactIssueRow();
            issue.setCustomerId(asLong(row[0]));
            issue.setCustomerName((String) row[1]);
            issue.setShopName((String) row[2]);
            issue.setCityName((String) row[3]);
            issue.setMobileNo(recorded == null ? "" : recorded.trim());
            issue.setAlternateMobileNo(alternate == null ? "" : alternate.trim());
            issue.setUsingAlternate(usingAlternate);
            issue.setBalance(MoneyRules.money(asDecimal(row[5])));
            issue.setLastSaleDate(row[7] == null ? null : asLocalDate(row[7]));
            issue.setSaleCount(asLong(row[8]));
            issue.setStatus(status.name());
            issue.setReason(usingAlternate
                    ? "The main number is unusable (" + MobileNumberRules.describe(primaryStatus)
                      + "); messages go to the second number."
                    : MobileNumberRules.describe(status));

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

            // Grouped on the number a message would actually be sent to, so two
            // customers collide only when the dispatcher would really reach the same
            // phone for both.
            byNumber.computeIfAbsent(MobileNumberRules.normalise(effective), key -> new ArrayList<>()).add(issue);
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
     * Sets one of a customer's two mobile numbers, and nothing else.
     *
     * Deliberately not the existing PUT /user/customers/{id}, which takes a whole
     * CustomerDTO: correcting a phone number through that endpoint means resending
     * every other field, and anything the caller leaves out is written back as
     * null. That is the same shape of bug as the stub-Customer merge that was
     * nulling city_id, and it has no place in a data-cleanup screen.
     *
     * @param alternate true to set the second number rather than the main one
     */
    @Transactional
    public Customer updateMobileNumber(Long customerId, String mobileNo, boolean alternate) {
        Customer customer = customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer " + customerId + " was not found."));

        /*
         * Clearing the second number is allowed; clearing the only one is not.
         *
         * A wrong second number should be removable - that is half the point of having
         * one - but blanking the main number would take a reachable customer off the
         * list by making them unreachable, which is the opposite of what this screen is
         * for. So an empty value is accepted only for the alternate, and only when it
         * is not the number currently carrying the customer.
         */
        boolean clearing = mobileNo == null || mobileNo.isBlank();
        if (clearing) {
            if (!alternate) {
                throw new IllegalArgumentException(
                        "The main number cannot be removed. Replace it with a correct one instead.");
            }
            if (!MobileNumberRules.isValid(customer.getMobileNo())) {
                throw new IllegalStateException("This customer is only reachable on the second number,"
                        + " because the main one is unusable. Correct the main number first.");
            }
            logger.info("Clearing the second number for customer {} ({})", customerId, customer.getName());
            customer.setAlternateMobileNo(null);
            return customerRepository.save(customer);
        }

        String normalised = MobileNumberRules.normalise(mobileNo);
        MobileNumberRules.Status status = MobileNumberRules.classify(normalised);

        if (status != MobileNumberRules.Status.VALID) {
            throw new IllegalArgumentException("\"" + mobileNo.trim()
                    + "\" cannot be used: " + MobileNumberRules.describe(status) + ".");
        }

        // The same number twice on one customer is not a second contact, it is a
        // typo that makes the fallback useless.
        String other = alternate ? customer.getMobileNo() : customer.getAlternateMobileNo();
        if (other != null && normalised.equals(MobileNumberRules.normalise(other))) {
            throw new IllegalArgumentException(
                    "That is already this customer's other number. A second number has to be a different phone.");
        }

        // Refuse a number already on another customer, rather than silently adding
        // to the shared-number problem this screen exists to clear up. Both columns
        // are checked: a number held as somebody else's fallback still reaches their
        // phone, so a statement sent to it would go to the wrong shop.
        for (Customer holder : customerRepository.findByEitherMobileNo(normalised)) {
            if (!holder.getId().equals(customerId)) {
                throw new IllegalStateException("That number is already recorded for "
                        + holder.getName() + ". Two customers cannot share a number if either is to be"
                        + " sent a statement.");
            }
        }

        logger.info("Updating the {} number for customer {} ({})",
                alternate ? "second" : "main", customerId, customer.getName());
        if (alternate) {
            customer.setAlternateMobileNo(normalised);
        } else {
            customer.setMobileNo(normalised);
        }
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
