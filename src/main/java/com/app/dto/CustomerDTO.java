package com.app.dto;


import lombok.Data;


@Data
public class CustomerDTO {
    private String name;
    private String mobileNo;
    /** Optional second number for the same shop; the send path falls back to it. */
    private String alternateMobileNo;
    private String address;
    private String shopName;
    private boolean obsolete;
    private String balanceAmount;
    private Long route;
    private Long city;

}

