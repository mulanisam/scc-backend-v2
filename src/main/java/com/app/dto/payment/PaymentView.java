package com.app.dto.payment;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.app.entity.CustomerPayment;

/**
 * A recorded receipt, as a screen needs it back.
 *
 * A projection rather than the entity, for the reason the trading entry needed one:
 * CustomerPayment holds a Customer, and when that Customer arrives as a lazy proxy - which
 * it does whenever the customer was reached through something else first, as a party
 * payment reaches it through the party - Jackson tries to serialise the proxy's interceptor
 * and fails:
 *
 *   No serializer found for class ByteBuddyInterceptor
 *   (through reference chain: CustomerPayment["customer"]->Customer$HibernateProxy["hibernateLazyInitializer"])
 *
 * That produced a 500 on a payment that had saved perfectly - money recorded, ledger
 * posted, balance moved - which is the worst shape a bug can take on a payment screen: the
 * operator sees a failure and enters it again.
 *
 * Built inside the transaction that saved it, so touching the proxy still initialises it.
 * A record built in the controller would throw LazyInitializationException instead -
 * spring.jpa.open-in-view is false, so the session is closed by then.
 *
 * It also keeps a customer's balance and credit limit out of a response whose job is to
 * confirm a receipt.
 */
public record PaymentView(
        Long id,
        Long customerId,
        String customerName,
        LocalDate paymentDate,
        BigDecimal amount,
        String paymentMode,
        String transactionReference,
        String remarks,
        String receivedBy) {

    public static PaymentView of(CustomerPayment payment) {
        return new PaymentView(
                payment.getId(),
                payment.getCustomer() == null ? null : payment.getCustomer().getId(),
                payment.getCustomer() == null ? null : payment.getCustomer().getName(),
                payment.getPaymentDate(),
                payment.getAmount(),
                payment.getPaymentMode(),
                payment.getTransactionReference(),
                payment.getRemarks(),
                payment.getReceivedBy());
    }
}
