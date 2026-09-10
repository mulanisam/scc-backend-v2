package com.app.entity;


import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.persistence.Entity;
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

    /** Opted in, not opted out. The one question the send path asks. */
    public boolean isWhatsappAllowed() {
        return whatsappOptInAt != null && !whatsappOptOut;
    }
   
    
//    @ManyToOne
//    @JoinColumn(name = "route_id", nullable = false)
//    private Route route;

    @ManyToOne
    @JoinColumn(name = "city_id", nullable = false)
    @JsonIgnoreProperties("customers")
    private City city;

}

