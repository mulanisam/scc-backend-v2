package com.app.dto.contact;

import lombok.Data;

/**
 * A corrected mobile number, on its own.
 *
 * One field rather than a CustomerDTO, so a contact-cleanup screen cannot blank
 * another column by omitting it.
 */
@Data
public class MobileNumberUpdateRequest {

    private String mobileNo;

    /**
     * True to set the second number for the shop instead of the main one.
     *
     * A flag rather than a second endpoint, because every rule around it is the same:
     * the number has to be valid, it has to be a different phone from the customer's
     * other one, and it must not already belong to somebody else. Two endpoints would
     * have meant two copies of those three checks.
     *
     * Defaults to false, so an existing caller keeps editing the main number.
     */
    private boolean alternate;
}
