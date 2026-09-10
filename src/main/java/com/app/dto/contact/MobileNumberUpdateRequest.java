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
}
