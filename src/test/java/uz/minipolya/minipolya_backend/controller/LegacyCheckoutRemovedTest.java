package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F1: the Payme/Click hosted checkout is gone. Its webhooks were public, checked no
 * signature and took a missing status for success, so anyone holding a payment id could
 * confirm a booking nobody paid for. Nothing under /webhooks but Telegram is public now,
 * no path left can turn a callback into a confirmed booking, and the checkout that handed
 * out the ids does not exist.
 */
@DatabaseTest
class LegacyCheckoutRemovedTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private BookingRepository bookings;
    @Autowired
    private PaymentRepository payments;

    private User player;
    private String playerToken;
    private Stadium stadium;
    private Booking booking;
    private Payment legacyRow;

    @BeforeEach
    void setUp() {
        player = data.user("Legacy Player");
        playerToken = data.bearer(player);
        stadium = data.stadium(data.owner("Legacy Owner"), "Legacy Arena");
        booking = data.booking(stadium, player,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.HOURS), "pending");
        // A row exactly as the old checkout left it: pending, with the guessable mock txn id.
        legacyRow = data.save(Payment.builder()
                .booking(booking)
                .user(player)
                .amount(booking.getPrice())
                .ownerAmount(booking.getPrice())
                .platformAmount(0L)
                .commissionPercent(0)
                .method(Payment.Method.CARD)
                .paymentType(Payment.PaymentType.BOOKING)
                .provider(Payment.Provider.PAYME)
                .status(Payment.Status.PENDING)
                .build());
        legacyRow.setProviderTxnId("mock-" + legacyRow.getId());
        legacyRow = data.save(legacyRow);
        payments.flush(); // really in the table, so a request could have found it
    }

    private String callback() {
        return "{\"txnId\":\"mock-" + legacyRow.getId() + "\"}"; // no status: the old code read that as success
    }

    @Test
    void theOldWebhooksAreNotPublic() throws Exception {
        for (String path : new String[]{"/webhooks/payme", "/webhooks/click"}) {
            mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(callback()))
                    .andExpect(status().isUnauthorized());
        }
        assertNothingConfirmed();
    }

    @Test
    void theOldWebhooksDoNotExistEvenWithAToken() throws Exception {
        for (String path : new String[]{"/webhooks/payme", "/webhooks/click"}) {
            mvc.perform(post(path).header("Authorization", playerToken)
                            .contentType(MediaType.APPLICATION_JSON).content(callback()))
                    .andExpect(status().isNotFound());
        }
        assertNothingConfirmed();
    }

    @Test
    void theCheckoutThatHandedOutPaymentIdsIsGone() throws Exception {
        mvc.perform(post("/api/payments/initiate").header("Authorization", playerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":\"" + booking.getId() + "\",\"provider\":\"payme\"}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
        assertThat(payments.findByBookingId(booking.getId())).hasSize(1); // only the old row
        assertNothingConfirmed();
    }

    @Test
    void aGameShareCannotBePaidWithoutASavedCard() throws Exception {
        Game game = data.casualGame(stadium, player,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).truncatedTo(ChronoUnit.HOURS));
        mvc.perform(post("/api/games/" + game.getId() + "/pay").param("provider", "payme")
                        .header("Authorization", playerToken))
                .andExpect(status().isBadRequest());
    }

    private void assertNothingConfirmed() {
        assertThat(bookings.findById(booking.getId()).orElseThrow().getStatus()).isEqualTo("pending");
        assertThat(payments.findById(legacyRow.getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.PENDING);
    }
}
