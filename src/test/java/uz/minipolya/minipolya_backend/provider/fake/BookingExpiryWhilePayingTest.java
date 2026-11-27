package uz.minipolya.minipolya_backend.provider.fake;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.scheduler.BookingExpiryJob;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A booking keeps its slot while its card payment is still on its way, against the real
 * query the job runs ({@code BookingRepository.findExpirablePending}).
 *
 * <p>Why: ATMOS answers {@code pay/apply} with {@code STPIMS-ERR-041} ("Проблема соединения")
 * for payments it has in fact taken, and asking {@code pay/get} is how we find out. Expiring
 * the booking meanwhile freed the slot, so when the payment turned out paid the money had to
 * be given straight back and the player's slot was gone — for a payment that was fine all
 * along. The {@code 0093} card is the case asking cannot settle: nothing is ever decided, so
 * the slot must be held until the reconcile job gives up on the charge.
 */
@DatabaseTest
class BookingExpiryWhilePayingTest extends FakeProviderFlowSupport {

    @Autowired
    private BookingExpiryJob expiryJob;

    /** Puts the booking's hold in the past, as the job would find it a minute later. */
    private void holdRanOut(UUID bookingId) {
        Booking booking = bookings.findById(bookingId).orElseThrow();
        booking.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        bookings.saveAndFlush(booking);
    }

    @Test
    void aBookingWhosePaymentIsStillOnItsWayKeepsItsSlot() throws Exception {
        User player = data.user("Unresolved Player");
        UUID unclear = addCard(player, "8600123412340093");
        UUID bookingId = bookingId(book(player, unclear, "FULL", slot(6, 18))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentState").value("processing")));
        assertThat(onlyPayment(bookingId, Payment.PaymentType.BOOKING).getStatus())
                .isEqualTo(Payment.Status.PROCESSING);

        holdRanOut(bookingId);
        expiryJob.expirePendingBookings();

        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo("pending");
    }

    /**
     * And once the charge is settled the slot is freed as it always was — nothing waits for
     * ever. A failed payment is what the reconcile job leaves behind when it gives up.
     */
    @Test
    void theSameBookingExpiresOnceThePaymentIsSettled() throws Exception {
        User player = data.user("Settled Player");
        UUID unclear = addCard(player, "8600123412340093");
        UUID bookingId = bookingId(book(player, unclear, "FULL", slot(6, 19)).andExpect(status().isOk()));

        Payment row = onlyPayment(bookingId, Payment.PaymentType.BOOKING);
        row.setStatus(Payment.Status.FAILED);
        payments.saveAndFlush(row);
        holdRanOut(bookingId);
        expiryJob.expirePendingBookings();

        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo("expired");
    }

    /** A booking with no card payment at all is unaffected: it expires as before. */
    @Test
    void aBookingWithNoPaymentAtAllStillExpires() throws Exception {
        User player = data.user("Declined Player");
        UUID declining = addCard(player, "8600123412340002");
        UUID bookingId = bookingId(book(player, declining, "FULL", slot(6, 20)).andExpect(status().isOk()));
        assertThat(onlyPayment(bookingId, Payment.PaymentType.BOOKING).getStatus())
                .isEqualTo(Payment.Status.FAILED);

        holdRanOut(bookingId);
        expiryJob.expirePendingBookings();

        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo("expired");
    }
}
