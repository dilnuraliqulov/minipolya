package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Stadium;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** The all-or-nothing cancellation window. */
class CancellationPolicyTest {

    private final CancellationPolicy policy = new CancellationPolicy();
    private final OffsetDateTime now = OffsetDateTime.of(2026, 3, 1, 12, 0, 0, 0, ZoneOffset.UTC);

    private Booking bookingStartingIn(long hours, int cancellationHours) {
        return Booking.builder()
                .stadium(Stadium.builder().cancellationHours(cancellationHours).build())
                .startsAt(now.plusHours(hours))
                .build();
    }

    @Test
    void theDeadlineIsTheWindowBeforeKickOff() {
        assertThat(policy.deadline(bookingStartingIn(48, 24))).isEqualTo(now.plusHours(24));
    }

    @Test
    void wellBeforeTheDeadlineIsRefundable() {
        assertThat(policy.refundable(bookingStartingIn(48, 24), now)).isTrue();
    }

    @Test
    void exactlyOnTheDeadlineIsStillRefundable() {
        assertThat(policy.refundable(bookingStartingIn(24, 24), now)).isTrue();
    }

    @Test
    void aMinutePastTheDeadlineIsNot() {
        Booking booking = bookingStartingIn(24, 24);
        assertThat(policy.refundable(booking, now.plusMinutes(1))).isFalse();
    }

    @Test
    void insideTheWindowIsNotRefundable() {
        assertThat(policy.refundable(bookingStartingIn(5, 24), now)).isFalse();
    }

    @Test
    void aStartedBookingIsNotRefundable() {
        assertThat(policy.refundable(bookingStartingIn(-1, 24), now)).isFalse();
    }

    @Test
    void aZeroHourWindowIsRefundableRightUpToKickOff() {
        assertThat(policy.refundable(bookingStartingIn(1, 0), now)).isTrue();
        assertThat(policy.refundable(bookingStartingIn(0, 0), now)).isTrue();
        assertThat(policy.refundable(bookingStartingIn(-1, 0), now)).isFalse();
    }
}
