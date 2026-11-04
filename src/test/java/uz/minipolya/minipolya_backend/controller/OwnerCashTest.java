package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Booking;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R37: {@code POST /api/owner/bookings/{id}/cash} — the owner takes what is still owed in cash. */
@DatabaseTest
class OwnerCashTest {

    private static final long PLAYER_PRICE = 20_310_000L;
    private static final long DEPOSIT = 5_000_000L;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PaymentRepository paymentRepository;

    private User owner;
    private String ownerToken;
    private User player;
    private Stadium stadium;
    private Booking booking;

    @BeforeEach
    void setUp() {
        owner = data.owner("Cash Owner");
        ownerToken = data.bearer(owner);
        player = data.user("Cash Player");
        stadium = data.stadium(owner, "Cash Arena");
        booking = partiallyPaid(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.HOURS));
    }

    private Booking partiallyPaid(OffsetDateTime startsAt) {
        Booking b = data.booking(stadium, player, startsAt, "confirmed");
        b.setCardPrice(PLAYER_PRICE);
        b.setPaidAmount(DEPOSIT);
        b.setPaymentStatus(Booking.PaymentStatus.PARTIALLY_PAID);
        return bookingRepository.saveAndFlush(b);
    }

    private ResultActions cash(UUID bookingId, String token) throws Exception {
        var request = post("/api/owner/bookings/" + bookingId + "/cash");
        if (token != null) {
            request.header("Authorization", token);
        }
        return mvc.perform(request);
    }

    @Test
    void recordsTheRemainingAmountAndAnswersTheOwnerView() throws Exception {
        cash(booking.getId(), ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cash recorded"))
                .andExpect(jsonPath("$.data.id").value(booking.getId().toString()))
                .andExpect(jsonPath("$.data.paymentStatus").value("paid"))
                .andExpect(jsonPath("$.data.remainingAmount").value(0))
                .andExpect(jsonPath("$.data.paidAmount").value(PLAYER_PRICE))
                .andExpect(jsonPath("$.data.ownerPrice").exists());

        assertThat(paymentRepository.findByBookingId(booking.getId()))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.getMethod()).isEqualTo(Payment.Method.CASH);
                    assertThat(p.getAmount()).isEqualTo(PLAYER_PRICE - DEPOSIT);
                });

        cash(booking.getId(), ownerToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Already paid"));
    }

    @Test
    void anUnpaidManualBookingCanBeSettledInCash() throws Exception {
        Booking walkIn = data.walkIn(stadium, "Walk-in", "+998901112233",
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).truncatedTo(ChronoUnit.HOURS));
        cash(walkIn.getId(), ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("paid"));
    }

    @Test
    void nothingToCompleteIs400() throws Exception {
        String message = "This booking has no part payment to complete";
        booking.setStatus("cancelled_by_user");
        bookingRepository.saveAndFlush(booking);
        cash(booking.getId(), ownerToken).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));

        // A pending app booking (unpaid, on hold) is not the owner's to settle.
        Booking pending = data.booking(stadium, player,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(4).truncatedTo(ChronoUnit.HOURS), "pending");
        cash(pending.getId(), ownerToken).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));

        // A game booking's remainders belong to its teams.
        Booking game = partiallyPaid(OffsetDateTime.now(ZoneOffset.UTC).plusDays(5).truncatedTo(ChronoUnit.HOURS));
        game.setSource("game");
        bookingRepository.saveAndFlush(game);
        cash(game.getId(), ownerToken).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
    }

    @Test
    void aRemainderStillBeingChargedInTheAppIs409() throws Exception {
        Payment inFlight = data.cardPayment(booking, PLAYER_PRICE - DEPOSIT, PLAYER_PRICE - DEPOSIT, 0L, null,
                Payment.Status.PROCESSING);
        inFlight.setPaymentType(Payment.PaymentType.REMAINDER);
        data.save(inFlight);

        cash(booking.getId(), ownerToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A payment for this booking is being processed"));
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getPaymentStatus())
                .isEqualTo(Booking.PaymentStatus.PARTIALLY_PAID);

        // Declined in the end: the owner can take the cash.
        inFlight.setStatus(Payment.Status.FAILED);
        data.save(inFlight);
        cash(booking.getId(), ownerToken).andExpect(status().isOk());
    }

    @Test
    void accessRules() throws Exception {
        cash(booking.getId(), data.bearer(data.owner("Another Owner")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not own this stadium"));
        cash(UUID.fromString("00000000-0000-0000-0000-000000000000"), ownerToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Booking not found"));
        cash(booking.getId(), data.bearer(player)).andExpect(status().isForbidden());
        cash(booking.getId(), null).andExpect(status().isUnauthorized());
    }
}
