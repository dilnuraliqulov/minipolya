package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse.PaymentState;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.provider.DeclineReason;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code paymentState} on a booking: where its latest card payment stands — paid,
 * processing, declined, failed or none — and always consistent with {@code declineReason}:
 * declined exactly when a reason is present.
 */
class BookingPaymentStateTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 6, 9, 0, 0, 0, ZoneOffset.UTC);
    private final List<Payment> payments = new ArrayList<>();
    private int minutes;

    private Payment add(Payment.Status status, Payment.PaymentType type, Payment.Method method, long amount,
                        DeclineReason reason) {
        Payment p = Payment.builder().id(UUID.randomUUID()).status(status).paymentType(type).method(method)
                .amount(amount).declineReason(reason).createdAt(T0.plusMinutes(minutes++)).build();
        payments.add(p);
        return p;
    }

    private Payment card(Payment.Status status) {
        return add(status, Payment.PaymentType.BOOKING, Payment.Method.CARD, 100_000L, null);
    }

    private PaymentState state() {
        PaymentState state = BookingResponseFactory.paymentStateOf(payments);
        DeclineReason reason = BookingResponseFactory.declineReasonOf(payments);
        // The rule the frontend relies on.
        assertThat(state == PaymentState.DECLINED).isEqualTo(reason != null);
        return state;
    }

    @Test
    void noCardPaymentIsNone() {
        assertThat(state()).isEqualTo(PaymentState.NONE);
        add(Payment.Status.COMPLETED, Payment.PaymentType.BOOKING, Payment.Method.CASH, 100_000L, null);
        assertThat(state()).isEqualTo(PaymentState.NONE);
    }

    @Test
    void theLatestCardPaymentDecides() {
        card(Payment.Status.FAILED).setDeclineReason(DeclineReason.CARD_DECLINED);
        assertThat(state()).isEqualTo(PaymentState.DECLINED);

        card(Payment.Status.PROCESSING);
        assertThat(state()).isEqualTo(PaymentState.PROCESSING);

        payments.getLast().setStatus(Payment.Status.PAID);
        assertThat(state()).isEqualTo(PaymentState.PAID);
    }

    @Test
    void aFailureWithoutARefusalIsFailedNotDeclined() {
        card(Payment.Status.FAILED);
        assertThat(state()).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void moneyThatArrivedIsPaidEvenIfGivenBackSince() {
        card(Payment.Status.REFUNDED);
        assertThat(state()).isEqualTo(PaymentState.PAID);
    }

    @Test
    void aDeclinedRemainderAfterAPaidDepositIsDeclined() {
        card(Payment.Status.PAID);
        add(Payment.Status.FAILED, Payment.PaymentType.REMAINDER, Payment.Method.CARD, 50_000L,
                DeclineReason.INSUFFICIENT_FUNDS);
        assertThat(state()).isEqualTo(PaymentState.DECLINED);
    }

    @Test
    void refundRowsAreNotAttempts() {
        card(Payment.Status.REFUNDED);
        add(Payment.Status.REFUNDED, Payment.PaymentType.REFUND, Payment.Method.CARD, -100_000L, null);
        assertThat(state()).isEqualTo(PaymentState.PAID);
    }
}
