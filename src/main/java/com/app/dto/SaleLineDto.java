package com.app.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
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

    @NotNull(message = "Every sale line must name a customer")
    private Long customerId;

    @PositiveOrZero(message = "Birds cannot be negative")
    private Integer birds;

    @NotNull(message = "Weight is required")
    @DecimalMin(value = "0.001", message = "Weight must be greater than zero")
    @Digits(integer = 9, fraction = 3, message = "Weight may have at most 3 decimal places")
    private BigDecimal kilograms;

    @NotNull(message = "Rate is required")
    @DecimalMin(value = "0.01", message = "Rate must be greater than zero")
    @Digits(integer = 8, fraction = 4, message = "Rate may have at most 4 decimal places")
    private BigDecimal rate;

    /**
     * Amount as calculated by the client. Not trusted: the server recomputes it
     * from kilograms and rate and rejects the request if the two disagree.
     */
    @PositiveOrZero(message = "Amount cannot be negative")
    @Digits(integer = 12, fraction = 2, message = "Amount may have at most 2 decimal places")
    private BigDecimal amount;

    @PositiveOrZero(message = "Payment cannot be negative")
    @Digits(integer = 12, fraction = 2, message = "Payment may have at most 2 decimal places")
    private BigDecimal payment;

    /** Client-calculated outstanding balance; recomputed server-side. */
    private BigDecimal pending;

    private String paymentMode;

    private String description;
}
