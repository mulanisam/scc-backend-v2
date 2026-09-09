package com.app.utility;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public class SmsMessageBuilder {

    public static String buildMarathiSms(String customerName, double amount) {
        LocalDate today = LocalDate.now();
        String formattedDate = today.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"));

        return String.format(
            "नमस्कार %s, दिनांक %s रोजी तुमचा विक्री व्यवहार ₹%.2f रेकॉर्ड करण्यात आला आहे. धन्यवाद!",
            customerName, formattedDate, amount
        );
    }
}

