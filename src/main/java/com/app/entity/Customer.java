package com.app.entity;


import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.app.utility.MobileNumberRules;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Customer {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String mobileNo;

    /**
     * A second number for the same shop.
     *
     * A shop is reached on whichever phone is answered, and with one field the person
     * entering data had to pick one and lose the other. That is what the eleven shared
     * numbers in this data look like - somebody recorded the number they had, against
     * everyone who uses it.
     *
     * {@link #messagingNumber()} is what the send path asks for; this is a fallback,
     * never a second recipient. One message per customer, to one phone.
     */
    private String alternateMobileNo;
    private String address;
    private String shopName;
    private boolean obsolete;
    private BigDecimal balanceAmount;
    
    // Credit limit management (optional)
    private boolean creditLimitEnabled = false;
    private BigDecimal creditLimit;

    /*
     * WhatsApp consent.
     *
     * Separate from having a mobile number, because they answer different
     * questions: whether we can reach this customer, and whether they have agreed
     * to be reached this way. A ledger statement carries a balance, so a customer
     * who has not opted in does not get one - existing customers start opted out,
     * since nobody has agreed to anything yet.
     *
     * whatsappOptOut is checked before every send and is never cleared
     * automatically; a customer who asked to stop has to ask to resume.
     */
    private LocalDateTime whatsappOptInAt;
    private boolean whatsappOptOut = false;
    private LocalDateTime whatsappOptOutAt;
    /** Why messaging is off for this customer, when it is. */
    private String messagingNotes;

    /**
     * Retail shop on a route, or wholesale party.
     *
     * Both are customers in the only sense the ledger cares about - they owe money and
     * get a statement - but they are different businesses, and mixing them put eleven
     * wholesale parties holding 44 lakh inside every route report and trip sheet. They
     * used to be told apart by sitting on a route called "Route no 9 Trading", which is
     * how a reporting distinction ended up encoded as a delivery round that does not
     * exist.
     *
     * The record stays a customer after moving to Trading, deliberately: that is what
     * keeps its ledger, its statement and its daily message working.
     */
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false, length = 20)
    private Channel channel = Channel.RETAIL;

    public enum Channel {
        RETAIL,
        TRADING
    }

    public boolean isTrading() {
        return channel == Channel.TRADING;
    }

    /** Opted in, not opted out. The one question the send path asks. */
    public boolean isWhatsappAllowed() {
        return whatsappOptInAt != null && !whatsappOptOut;
    }

    /**
     * The number a message should go to, or null if there is no usable one.
     *
     * Primary first, alternate only as a fallback - not both. A statement carries a
     * balance, so sending it twice to two phones doubles the exposure of a figure the
     * customer may not want seen, and doubles the provider cost for no benefit.
     *
     * Deliberately a method on the entity rather than a rule in the messaging service:
     * the contact-quality screen and the send path have to agree on which number would
     * be used, or the screen would report a customer reachable on a number the sender
     * never picks.
     */
    public String messagingNumber() {
        if (MobileNumberRules.isValid(mobileNo)) {
            return MobileNumberRules.normalise(mobileNo);
        }
        if (MobileNumberRules.isValid(alternateMobileNo)) {
            return MobileNumberRules.normalise(alternateMobileNo);
        }
        return null;
    }

    /**
     * True when either number can actually be dialled.
     *
     * @JsonIgnore because this is a derived fact, not a field. Lombok's @Data plus the
     * bean naming convention would otherwise publish it as "contactable", and the Master
     * Data form builds its inputs from whatever keys the customer JSON carries - so it
     * would appear as an editable "Contactable" text box that writes nowhere. Screens
     * that need this get it from ContactIssueRow, which is shaped for them.
     */
    @JsonIgnore
    public boolean isContactable() {
        return messagingNumber() != null;
    }
   
    
//    @ManyToOne
//    @JoinColumn(name = "route_id", nullable = false)
//    private Route route;

    @ManyToOne
    @JoinColumn(name = "city_id", nullable = false)
    @JsonIgnoreProperties("customers")
    private City city;

}

