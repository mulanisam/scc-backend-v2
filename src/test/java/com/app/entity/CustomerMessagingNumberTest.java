package com.app.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which of a customer's two numbers a message goes to.
 *
 * The rule is one phone, chosen here, and it has to be chosen in exactly one place: the
 * contact-quality screen reports who is reachable and the dispatcher decides where to
 * send, and if those two disagreed the 130-number work list would never empty - filling
 * in a second number would make a customer reachable without taking them off the list,
 * or worse, take them off a list while the dispatcher still refused to message them.
 */
class CustomerMessagingNumberTest {

    private Customer customer(String primary, String alternate) {
        Customer customer = new Customer();
        customer.setName("Swarup Bhale");
        customer.setMobileNo(primary);
        customer.setAlternateMobileNo(alternate);
        return customer;
    }

    @Test
    @DisplayName("The main number wins when it is usable")
    void primaryWins() {
        Customer both = customer("9975080207", "9822012345");

        assertEquals("9975080207", both.messagingNumber());
        assertTrue(both.isContactable());
    }

    @Test
    @DisplayName("The second number carries the customer when the main one cannot")
    void fallsBackToTheAlternate() {
        // The case the second column was added for: 118 customers have nothing in the
        // main field, and one of them owes over two lakh.
        assertEquals("9822012345", customer(null, "9822012345").messagingNumber());
        assertEquals("9822012345", customer("", "9822012345").messagingNumber());
        assertEquals("9822012345", customer("12345", "9822012345").messagingNumber());
        assertEquals("9822012345", customer("0000000000", "9822012345").messagingNumber());

        assertTrue(customer(null, "9822012345").isContactable());
    }

    @Test
    @DisplayName("Neither number usable means no number at all, not an empty string")
    void noUsableNumberIsNull() {
        // Null rather than "", so a caller that forgets to check gets a failure rather
        // than silently addressing a message to nowhere.
        assertNull(customer(null, null).messagingNumber());
        assertNull(customer("", "").messagingNumber());
        assertNull(customer("12345", "99999").messagingNumber());
        assertFalse(customer(null, null).isContactable());
    }

    @Test
    @DisplayName("Whichever number is chosen comes back normalised")
    void theChosenNumberIsNormalised() {
        // So the outbox, the duplicate check and the provider all see one spelling.
        // "+91 98220 12345" and "098220 12345" are the same phone.
        assertEquals("9822012345", customer("+91 98220 12345", null).messagingNumber());
        assertEquals("9822012345", customer(null, "098220 12345").messagingNumber());
        assertEquals("9822012345", customer("  9822012345  ", null).messagingNumber());
    }

    @Test
    @DisplayName("A bad main number does not hide a good second one")
    void aBadPrimaryDoesNotMaskTheAlternate() {
        // The failure this guards against: judging reachability on the primary alone.
        // The customer is contactable, and both the screen and the dispatcher have to
        // say so - otherwise recording a second number achieves nothing.
        Customer placeholderPrimary = customer("1234567890", "9822012345");

        assertTrue(placeholderPrimary.isContactable());
        assertEquals("9822012345", placeholderPrimary.messagingNumber());
    }
}
