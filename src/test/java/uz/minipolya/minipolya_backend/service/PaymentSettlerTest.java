package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Asking the provider straight away about a payment ATMOS left {@code processing} — the
 * {@code STPIMS-ERR-041} case, where the money moved and the apply said "connection problem".
 * Nothing here settles a payment itself: every attempt is a {@code reconcile}, the same call
 * the five-minute job makes.
 */
class PaymentSettlerTest {

    private static final UUID BOOKING = UUID.fromString("0190c3a1-0000-7000-8000-00000000000b");

    private final PaymentReconcileService reconcileService = mock(PaymentReconcileService.class);
    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final List<Duration> waits = new ArrayList<>();

    /** A clock the waits move on, so the budget is spent exactly as it would be in the request. */
    private Instant now = Instant.parse("2026-11-25T10:00:00Z");

    private PaymentSettler settler(int attempts, Duration delay) {
        return settler(attempts, delay, Duration.ofSeconds(20));
    }

    private PaymentSettler settler(int attempts, Duration delay, Duration budget) {
        AppProperties properties = new AppProperties();
        properties.getPayments().getSettleNow().setAttempts(attempts);
        properties.getPayments().getSettleNow().setDelay(delay);
        properties.getPayments().getSettleNow().setBudget(budget);
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return new PaymentSettler(reconcileService, paymentRepository, properties, clock, waited -> {
            waits.add(waited);
            now = now.plus(waited);
        });
    }

    private Payment row(UUID id, Payment.Status status) {
        return Payment.builder()
                .id(id)
                .booking(Booking.builder().id(BOOKING).build())
                .amount(5_500_000L)
                .method(Payment.Method.CARD)
                .status(status)
                .createdAt(OffsetDateTime.parse("2026-11-25T10:00:00Z"))
                .build();
    }

    /** The usual case: ATMOS took the money, pay/get says so, and the first ask is enough. */
    @Test
    void stopsAsSoonAsTheProviderSaysWhatHappened() {
        UUID id = UUID.randomUUID();
        // reconcile() is what moves the row; here it turns processing into paid.
        when(paymentRepository.findById(id))
                .thenReturn(Optional.of(row(id, Payment.Status.PROCESSING)));
        doAnswer(invocation -> {
            when(paymentRepository.findById(id)).thenReturn(Optional.of(row(id, Payment.Status.PAID)));
            return null;
        }).when(reconcileService).reconcile(id);

        assertThat(settler(6, Duration.ofSeconds(3)).settleNow(id)).isTrue();

        verify(reconcileService, times(1)).reconcile(id);
        // Settled on the first ask, so the player waited for nothing at all.
        assertThat(waits).isEmpty();
    }

    /** Still unknown after every attempt: the row stays as it is, for the reconcile job. */
    @Test
    void givesUpAfterItsAttemptsAndLeavesTheRowToTheJob() {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id))
                .thenReturn(Optional.of(row(id, Payment.Status.PROCESSING)));

        assertThat(settler(3, Duration.ofSeconds(2)).settleNow(id)).isFalse();

        verify(reconcileService, times(3)).reconcile(id);
        // Between the asks, never after the last one.
        assertThat(waits).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
    }

    /** A provider still not answering is exactly what the job is for: never an error here. */
    @Test
    void aProviderThatThrowsIsNotAnError() {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id))
                .thenReturn(Optional.of(row(id, Payment.Status.PROCESSING)));
        doAnswer(invocation -> {
            throw new IllegalStateException("ATMOS is still unreachable");
        }).when(reconcileService).reconcile(id);

        assertThat(settler(2, Duration.ZERO).settleNow(id)).isFalse();

        verify(reconcileService, times(2)).reconcile(id);
    }

    /** attempts=0 turns it off: nothing is asked and nothing waits. */
    /**
     * However many attempts are configured, no request is held up longer than the budget:
     * one ATMOS call may itself take thirty seconds.
     */
    @Test
    void neverHoldsARequestLongerThanItsBudget() {
        UUID id = UUID.randomUUID();
        when(paymentRepository.findById(id))
                .thenReturn(Optional.of(row(id, Payment.Status.PROCESSING)));

        assertThat(settler(10, Duration.ofSeconds(3), Duration.ofSeconds(7)).settleNow(id)).isFalse();

        // 3 + 3 = 6 s of waiting; a third wait would leave no room for the attempt after it.
        assertThat(waits).containsExactly(Duration.ofSeconds(3), Duration.ofSeconds(3));
        verify(reconcileService, times(3)).reconcile(id);
    }

    @Test
    void canBeTurnedOff() {
        UUID id = UUID.randomUUID();

        assertThat(settler(0, Duration.ofSeconds(3)).settleNow(id)).isFalse();

        verify(reconcileService, never()).reconcile(any());
        assertThat(waits).isEmpty();
    }

    /**
     * From a booking: its latest card payment that is still on its way. A cash row, a
     * zero-amount row and a payment that already failed are not on their way.
     */
    @Test
    void findsTheBookingsLatestCardPaymentThatIsStillOnItsWay() {
        UUID older = UUID.randomUUID();
        UUID latest = UUID.randomUUID();
        Payment cash = row(UUID.randomUUID(), Payment.Status.PROCESSING);
        cash.setMethod(Payment.Method.CASH);
        Payment failed = row(UUID.randomUUID(), Payment.Status.FAILED);
        Payment first = row(older, Payment.Status.PROCESSING);
        first.setCreatedAt(OffsetDateTime.parse("2026-11-25T09:00:00Z"));
        when(paymentRepository.findByBookingId(BOOKING))
                .thenReturn(List.of(cash, failed, first, row(latest, Payment.Status.PROCESSING)));

        assertThat(settler(0, Duration.ZERO).inFlightPaymentOf(BOOKING)).isEqualTo(latest);
    }

    @Test
    void aBookingWithNothingOnItsWayIsNotAskedAbout() {
        when(paymentRepository.findByBookingId(BOOKING))
                .thenReturn(List.of(row(UUID.randomUUID(), Payment.Status.PAID)));

        assertThat(settler(6, Duration.ZERO).settleBookingNow(BOOKING)).isFalse();
        verify(reconcileService, never()).reconcile(any());
    }
}
