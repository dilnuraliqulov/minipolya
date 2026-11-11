package uz.minipolya.minipolya_backend.provider.fake;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R28: two taps on "Pay" at the same moment charge the card once — the booking row is
 * locked, so the second sees the first's payment and is refused. Real commits and threads,
 * so this runs outside the rolled-back test transaction and cleans up after itself.
 */
@DatabaseTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BookingPaymentRetryRaceTest extends FakeProviderFlowSupport {

    private final List<User> created = new ArrayList<>();

    @Test
    void parallelRetriesChargeOnce() throws Exception {
        User player = data.user("Race Retry Player");
        created.add(player);
        UUID declining = addCard(player, "8600123412340002");
        UUID good = addCard(player, "8600123412345555");
        UUID bookingId = bookingId(book(player, declining, "FULL", slot(5, 10)).andExpect(status().isOk()));
        CyclicBarrier together = new CyclicBarrier(2);

        List<CompletableFuture<Integer>> taps = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            taps.add(CompletableFuture.supplyAsync(() -> {
                try {
                    together.await(10, TimeUnit.SECONDS);
                    return mvc.perform(post("/api/bookings/" + bookingId + "/pay")
                                    .header("Authorization", data.bearer(player))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"cardId\":\"" + good + "\"}"))
                            .andReturn().getResponse().getStatus();
                } catch (Exception ex) {
                    throw new IllegalStateException(ex);
                }
            }));
        }
        List<Integer> statuses = new ArrayList<>();
        for (CompletableFuture<Integer> tap : taps) {
            statuses.add(tap.get(30, TimeUnit.SECONDS));
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(payments.findByBookingId(bookingId)).filteredOn(p -> p.getStatus() == Payment.Status.PAID).hasSize(1);
        long charged = payments.findByBookingId(bookingId).stream()
                .filter(p -> p.getStatus() == Payment.Status.PAID).mapToLong(Payment::getAmount).sum();
        assertThat(balance(good)).isEqualTo(START - charged);
        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo("confirmed");
    }

    /** These tests commit: remove everything they made. */
    @AfterEach
    void removeWhatWasCommitted() {
        UUID stadiumId = stadium.getId();
        List<UUID> paymentIds = jdbc.queryForList(
                "SELECT id FROM payments WHERE booking_id IN (SELECT id FROM bookings WHERE stadium_id = ?)",
                UUID.class, stadiumId);
        for (UUID paymentId : paymentIds) {
            jdbc.update("DELETE FROM audit_log WHERE entity_id = ?", paymentId);
        }
        jdbc.update("DELETE FROM payments WHERE booking_id IN (SELECT id FROM bookings WHERE stadium_id = ?)", stadiumId);
        jdbc.update("DELETE FROM audit_log WHERE entity_id IN (SELECT id FROM bookings WHERE stadium_id = ?)", stadiumId);
        jdbc.update("DELETE FROM audit_log WHERE entity_id = ?", stadiumId);
        jdbc.update("DELETE FROM stadiums WHERE id = ?", stadiumId);
        List<UUID> users = new ArrayList<>(created.stream().map(User::getId).toList());
        users.add(owner.getId());
        for (UUID userId : users) {
            jdbc.update("DELETE FROM audit_log WHERE user_id = ? OR entity_id = ?", userId, userId);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM saved_cards WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM owner_legal_details WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
