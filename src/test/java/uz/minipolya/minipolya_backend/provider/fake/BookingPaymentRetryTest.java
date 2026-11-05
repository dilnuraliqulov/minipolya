package uz.minipolya.minipolya_backend.provider.fake;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R28: {@code POST /api/bookings/{id}/pay} — the booking's player pays a pending booking
 * again (another card after a decline), for the same amount as when it was created, while
 * its hold lasts. The hold is never extended.
 */
@DatabaseTest
class BookingPaymentRetryTest extends FakeProviderFlowSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private jakarta.persistence.EntityManager entityManager;

    private ResultActions pay(User player, UUID bookingId, UUID cardId) throws Exception {
        return mvc.perform(post("/api/bookings/" + bookingId + "/pay").header("Authorization", data.bearer(player))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"" + cardId + "\"}"));
    }

    private List<Payment> attempts(UUID bookingId) {
        return payments.findByBookingId(bookingId).stream()
                .filter(p -> p.getPaymentType() == Payment.PaymentType.BOOKING)
                .sorted(Comparator.comparing(Payment::getCreatedAt))
                .toList();
    }

    @Test
    void afterADeclineAnotherCardPaysTheSameAmountAndConfirmsTheBooking() throws Exception {
        User player = data.user("Retry Full Player");
        UUID declining = addCard(player, "8600123412340002");
        UUID good = addCard(player, "8600123412341234");
        UUID bookingId = bookingId(book(player, declining, "FULL", slot(4, 10))
                .andExpect(jsonPath("$.data.paymentState").value("declined"))
                .andExpect(jsonPath("$.data.declineReason").value("CARD_DECLINED")));

        pay(player, bookingId, good)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("confirmed"))
                .andExpect(jsonPath("$.data.paymentStatus").value("paid"))
                .andExpect(jsonPath("$.data.paymentState").value("paid"))
                .andExpect(jsonPath("$.data.declineReason").doesNotExist())
                .andExpect(jsonPath("$.message").value("Booking confirmed and paid in full"));

        List<Payment> attempts = attempts(bookingId);
        assertThat(attempts).extracting(Payment::getStatus).containsExactly(Payment.Status.FAILED, Payment.Status.PAID);
        assertThat(attempts.get(1).getAmount()).isEqualTo(attempts.get(0).getAmount());
        assertThat(balance(good)).isEqualTo(START - attempts.get(1).getAmount());
        assertThat(bookings.findById(bookingId).orElseThrow().getExpiresAt()).isNull();
    }

    @Test
    void aDepositIsRetriedAsADeposit() throws Exception {
        User player = data.user("Retry Deposit Player");
        UUID declining = addCard(player, "8600123412340002");
        UUID good = addCard(player, "8600123412341235");
        UUID bookingId = bookingId(book(player, declining, "MINIMUM", slot(4, 11)));

        pay(player, bookingId, good)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("confirmed"))
                .andExpect(jsonPath("$.data.paymentStatus").value("partially_paid"));

        List<Payment> attempts = attempts(bookingId);
        assertThat(attempts.get(1).getAmount()).isEqualTo(attempts.get(0).getAmount());
        assertThat(attempts.get(1).getAmount()).isLessThan(bookings.findById(bookingId).orElseThrow().getCardPrice());
    }

    @Test
    void aSecondDeclineKeepsTheBookingPendingWithTheSameHoldAndSaysWhy() throws Exception {
        User player = data.user("Retry Again Player");
        UUID declining = addCard(player, "8600123412340002");
        UUID empty = addCard(player, "8600123412340051");
        UUID bookingId = bookingId(book(player, declining, "FULL", slot(4, 12)));
        OffsetDateTime hold = bookings.findById(bookingId).orElseThrow().getExpiresAt();

        pay(player, bookingId, empty)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.paymentStatus").value("unpaid"))
                .andExpect(jsonPath("$.data.paymentState").value("declined"))
                .andExpect(jsonPath("$.data.declineReason").value("INSUFFICIENT_FUNDS"));

        assertThat(bookings.findById(bookingId).orElseThrow().getExpiresAt()).isEqualTo(hold);
    }

    @Test
    void aBookingWhoseHoldRanOutCannotBePaid() throws Exception {
        User player = data.user("Retry Expired Player");
        UUID declining = addCard(player, "8600123412340002");
        UUID good = addCard(player, "8600123412341236");
        UUID bookingId = bookingId(book(player, declining, "FULL", slot(4, 13)));
        Booking booking = bookings.findById(bookingId).orElseThrow();
        booking.setExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        bookings.saveAndFlush(booking);

        pay(player, bookingId, good)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_EXPIRED"));
        assertThat(balance(good)).isEqualTo(START);
    }

    @Test
    void aPaymentStillProcessingBlocksARetry() throws Exception {
        User player = data.user("Retry Processing Player");
        UUID unclear = addCard(player, "8600123412340091");
        UUID good = addCard(player, "8600123412341237");
        UUID bookingId = bookingId(book(player, unclear, "FULL", slot(4, 14))
                .andExpect(jsonPath("$.data.paymentState").value("processing")));

        pay(player, bookingId, good)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_IN_PROGRESS"));
        assertThat(balance(good)).isEqualTo(START);
    }

    @Test
    void aBookingThatIsNotPendingCannotBePaidAgain() throws Exception {
        User player = data.user("Retry Confirmed Player");
        UUID good = addCard(player, "8600123412341238");
        UUID bookingId = bookingId(book(player, good, "FULL", slot(4, 15)));

        pay(player, bookingId, good)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_PENDING"));
    }

    @Test
    void anUnknownBookingIs404AndSomeoneElsesIs403() throws Exception {
        User player = data.user("Retry Owner Player");
        User other = data.user("Retry Other Player");
        UUID declining = addCard(player, "8600123412340002");
        UUID otherCard = addCard(other, "8600123412341239");
        UUID bookingId = bookingId(book(player, declining, "FULL", slot(4, 16)));

        pay(other, UUID.randomUUID(), otherCard).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Booking not found"));
        pay(other, bookingId, otherCard).andExpect(status().isForbidden());
        assertThat(balance(otherCard)).isEqualTo(START);
    }

    @Test
    void theRetryIsAudited() throws Exception {
        User player = data.user("Retry Audit Player");
        UUID declining = addCard(player, "8600123412340002");
        UUID good = addCard(player, "8600123412341240");
        UUID bookingId = bookingId(book(player, declining, "FULL", slot(4, 17)));

        String body = pay(player, bookingId, good).andReturn().getResponse().getContentAsString();

        assertThat((String) JsonPath.read(body, "$.data.status")).isEqualTo("confirmed");
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_log WHERE entity_id = ? AND action = 'BOOKING_PAYMENT_RETRIED'",
                Integer.class, bookingId)).isEqualTo(1);
    }
}
