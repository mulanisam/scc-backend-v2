package com.app.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * One customer line within a bulk sale entry.
 *
 * This replaces the untyped {@code Map<String, Object>} the bulk endpoint used
 * to accept. That map required each field to arrive as a specific JSON type -
 * kilograms, rate, payment and birds as strings; amount, pending and customerId
 * as numbers - and threw ClassCastException or NumberFormatException when the
 * client sent the other one. It only worked because the React form happened to
 * hold input values as strings.
 *
 * Jackson coerces both JSON strings and JSON numbers into these types, and an
 * empty string becomes null (see spring.jackson.deserialization
 * .accept-empty-string-as-null-object), which the money rules treat as zero.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SaleLineDto {

    private Long customerId;

    private Integer birds;

    private BigDecimal kilograms;

    private BigDecimal rate;

    /**
     * Amount as calculated by the client. Not trusted: the server recomputes it
     * from kilograms and rate and rejects the request if the two disagree.
     */
    private BigDecimal amount;

    private BigDecimal payment;

    /** Client-calculated outstanding balance; recomputed server-side. */
    private BigDecimal pending;

    private String paymentMode;

    private String description;
}
