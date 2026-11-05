package uz.minipolya.minipolya_backend.provider.fake;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.OwnerLegalDetailsRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.SavedCardRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.service.GameService;
import uz.minipolya.minipolya_backend.service.PaymentReconcileService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The business flows end to end, over HTTP, against the real FakePaymentProvider and its
 * database store — no mocks: cards are bound with fake numbers, charged, held, captured,
 * reversed and released, and each card's fake balance shows where the money went.
 */
@DatabaseTest
class PaymentFlowsWithFakeProviderTest extends FakeProviderFlowSupport {

    // ---- cards ----

    @Test
    void bindingShowsTheMaskedPhoneAndRefusesOtherCardsWithAClearCode() throws Exception {
        User player = data.user("Card Player");
        String phoneEnd = player.getPhone().substring(player.getPhone().length() - 2);

        mvc.perform(post("/api/cards").header("Authorization", data.bearer(player))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"8600123412341234\",\"expiry\":\"12/29\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maskedPhone").value("+998 ** *** ** " + phoneEnd));
        mvc.perform(post("/api/cards").header("Authorization", data.bearer(player))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"4111111111111111\",\"expiry\":\"12/29\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CARD_NOT_SUPPORTED"))
                .andExpect(jsonPath("$.message").value("Faqat Uzcard va Humo kartalari qabul qilinadi"));

        UUID cardId = addCard(player, "9860123412341234");
        SavedCard saved = savedCards.findById(cardId).orElseThrow();
        assertThat(saved.getMaskedNumber()).isEqualTo("9860 •••• •••• 1234");
        assertThat(saved.getCardType()).isEqualTo("humo");
    }

    // ---- ordinary bookings ----

    @Test
    void aFullBookingCancelledBeforeTheDeadlineGivesEverythingBack() throws Exception {
        User player = data.user("Full Player");
        UUID cardId = addCard(player, "8600123412341234");

        UUID bookingId = bookingId(book(player, cardId, "FULL", slot(3, 18))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("confirmed"))
                .andExpect(jsonPath("$.data.paymentStatus").value("paid")));
        Payment paid = onlyPayment(bookingId, Payment.PaymentType.BOOKING);
        long price = paid.getAmount();
        assertThat(paid.getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(paid.getPlatformAmount()).isZero();
        assertThat(paid.getProviderCommission()).isEqualTo(price - uz.minipolya.minipolya_backend.util.PriceCalculator.netAfterFee(price, 150));
        assertThat(balance(cardId)).isEqualTo(START - price);

        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", data.bearer(player)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("cancelled_by_user"));

        assertThat(payments.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(balance(cardId)).isEqualTo(START);
    }

    @Test
    void aDepositThenTheRemainderInTheAppPaysThePriceExactly() throws Exception {
        User player = data.user("Deposit Player");
        UUID cardId = addCard(player, "8600123412345555");

        UUID bookingId = bookingId(book(player, cardId, "MINIMUM", slot(3, 19))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("partially_paid")));
        long deposit = onlyPayment(bookingId, Payment.PaymentType.BOOKING).getAmount();

        mvc.perform(post("/api/bookings/" + bookingId + "/pay-remainder").header("Authorization", data.bearer(player))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"" + cardId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("paid"));

        Booking booking = bookings.findById(bookingId).orElseThrow();
        long remainder = onlyPayment(bookingId, Payment.PaymentType.REMAINDER).getAmount();
        assertThat(deposit + remainder).isEqualTo(booking.getCardPrice());
        assertThat(balance(cardId)).isEqualTo(START - booking.getCardPrice());
    }

    @Test
    void cashForTheRemainderNeverTouchesTheCard() throws Exception {
        User player = data.user("Cash Player");
        UUID cardId = addCard(player, "8600123412346666");
        UUID bookingId = bookingId(book(player, cardId, "MINIMUM", slot(3, 20)).andExpect(status().isOk()));
        long afterDeposit = balance(cardId);

        mvc.perform(post("/api/owner/bookings/" + bookingId + "/cash").header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("paid"));

        assertThat(balance(cardId)).isEqualTo(afterDeposit);
    }

    @Test
    void theOwnerCancellingGivesEverythingBack() throws Exception {
        User player = data.user("Owner Cancel Player");
        UUID cardId = addCard(player, "8600123412347777");
        UUID bookingId = bookingId(book(player, cardId, "FULL", slot(3, 21)).andExpect(status().isOk()));

        mvc.perform(delete("/api/owner/bookings/" + bookingId).header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("cancelled_by_owner"));

        assertThat(balance(cardId)).isEqualTo(START);
    }

    @Test
    void declinedAndInsufficientCardsLeaveTheBookingUnpaid() throws Exception {
        User player = data.user("Declined Player");
        for (String[] card : new String[][]{{"8600123412340002", "10"}, {"8600123412340051", "11"}, {"8600123412340054", "12"}}) {
            UUID cardId = addCard(player, card[0]);

            UUID bookingId = bookingId(book(player, cardId, "FULL", slot(4, Integer.parseInt(card[1])))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("pending"))
                    .andExpect(jsonPath("$.data.paymentStatus").value("unpaid")));

            assertThat(onlyPayment(bookingId, Payment.PaymentType.BOOKING).getStatus()).isEqualTo(Payment.Status.FAILED);
            assertThat(balance(cardId)).isEqualTo(START);
        }
    }

    @Test
    void anUnavailableProviderIsA502WithAClearMessage() throws Exception {
        User player = data.user("Unavailable Player");
        UUID cardId = addCard(player, "8600123412340099");

        book(player, cardId, "FULL", slot(4, 14))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("To'lov tizimi vaqtincha ishlamayapti. Birozdan so'ng qayta urinib ko'ring"));
    }

    @Test
    void aLostAnswerStaysProcessingAndTheReconcileJobSettlesIt() throws Exception {
        User player = data.user("Timeout Player");
        UUID wins = addCard(player, "8600123412340091");
        UUID loses = addCard(player, "8600123412340092");

        UUID paidLater = bookingId(book(player, wins, "FULL", slot(5, 15)).andExpect(status().isOk()));
        UUID neverPaid = bookingId(book(player, loses, "FULL", slot(5, 16)).andExpect(status().isOk()));
        Payment first = onlyPayment(paidLater, Payment.PaymentType.BOOKING);
        Payment second = onlyPayment(neverPaid, Payment.PaymentType.BOOKING);
        assertThat(first.getStatus()).isEqualTo(Payment.Status.PROCESSING);
        assertThat(second.getStatus()).isEqualTo(Payment.Status.PROCESSING);

        reconcileService.reconcile(first.getId());
        reconcileService.reconcile(second.getId());

        assertThat(payments.findById(first.getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(bookings.findById(paidLater).orElseThrow().getStatus()).isEqualTo("confirmed");
        assertThat(payments.findById(second.getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.FAILED);
        assertThat(balance(loses)).isEqualTo(START);
    }

    @Test
    void aRefundTheProviderRefusesIsSavedFailedThenRetriedAndMarkedByAnAdmin() throws Exception {
        User player = data.user("Refund Player");
        User admin = data.admin("Refund Admin");
        UUID cardId = addCard(player, "8600123412340098");
        UUID bookingId = bookingId(book(player, cardId, "FULL", slot(3, 9)).andExpect(status().isOk()));
        Payment paid = onlyPayment(bookingId, Payment.PaymentType.BOOKING);

        mvc.perform(post("/api/bookings/" + bookingId + "/cancel").header("Authorization", data.bearer(player)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("cancelled_by_user"));

        assertThat(payments.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.REFUND_FAILED);
        Payment refund = payments.findByRefundOfPaymentIdOrderByCreatedAtAsc(paid.getId()).getFirst();
        assertThat(refund.getStatus()).isEqualTo(Payment.Status.FAILED);
        assertThat(refund.getFailureReason()).contains("(-15)");
        assertThat(balance(cardId)).isEqualTo(START - paid.getAmount());

        mvc.perform(post("/api/admin/payments/" + refund.getId() + "/retry-refund").header("Authorization", data.bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("failed"));
        mvc.perform(post("/api/admin/payments/" + refund.getId() + "/mark-refunded").header("Authorization", data.bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Returned by bank transfer from the owner\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("refunded"));
        assertThat(payments.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    // ---- team games ----

    @Test
    void aTeamGameHoldsTheCreatorThenChargesTheJoinerAndCapturesTheHold() throws Exception {
        User leaderA = data.user("Leader A");
        User leaderB = data.user("Leader B");
        UUID cardA = addCard(leaderA, "8600123412341111");
        UUID cardB = addCard(leaderB, "9860123412342222");

        UUID gameId = createTeamGame(leaderA, team(leaderA, "Team A"), cardA, slot(3, 17));
        Payment hold = payments.findByGameId(gameId).getFirst();
        assertThat(hold.getStatus()).isEqualTo(Payment.Status.HELD);
        assertThat(hold.getPlatformAmount()).isPositive();
        assertThat(balance(cardA)).isEqualTo(START - hold.getAmount());
        // The platform's split nets exactly the 25 000 so'm fee after ATMOS's 1.5%.
        assertThat(hold.getPlatformAmount() - FakeCommission.of(hold.getPlatformAmount())).isEqualTo(2_500_000L);

        join(leaderB, gameId, team(leaderB, "Team B"), cardB).andExpect(status().isOk());

        assertThat(games.findById(gameId).orElseThrow().getStatus()).isEqualTo("confirmed");
        assertThat(payments.findById(hold.getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.CAPTURED);
        Payment joined = payments.findByGameId(gameId).stream()
                .filter(p -> !p.getId().equals(hold.getId())).findFirst().orElseThrow();
        assertThat(joined.getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(balance(cardB)).isEqualTo(START - joined.getAmount());
        assertThat(balance(cardA)).isEqualTo(START - hold.getAmount());
    }

    @Test
    void aCaptureThatFailsEndsTheGameAndGivesBothTeamsEverythingBack() throws Exception {
        User leaderA = data.user("Capture Leader A");
        User leaderB = data.user("Capture Leader B");
        UUID cardA = addCard(leaderA, "8600123412340097");
        UUID cardB = addCard(leaderB, "8600123412343333");
        UUID gameId = createTeamGame(leaderA, team(leaderA, "Capture A"), cardA, slot(3, 16));

        join(leaderB, gameId, team(leaderB, "Capture B"), cardB)
                .andExpect(status().isBadRequest());

        Game game = games.findById(gameId).orElseThrow();
        assertThat(game.getStatus()).startsWith("cancelled");
        assertThat(balance(cardA)).isEqualTo(START);
        assertThat(balance(cardB)).isEqualTo(START);
        List<Payment> teamPayments = payments.findByGameId(gameId);
        assertThat(teamPayments).extracting(Payment::getStatus)
                .doesNotContain(Payment.Status.HELD, Payment.Status.PAID, Payment.Status.CAPTURED);
    }

    @Test
    void aWaitingGamePastItsThreeHourPointIsCancelledAndTheHoldReleased() throws Exception {
        User leader = data.user("Waiting Leader");
        UUID cardId = addCard(leader, "8600123412344444");
        UUID gameId = createTeamGame(leader, team(leader, "Waiting Team"), cardId, slot(3, 15));
        assertThat(balance(cardId)).isLessThan(START);

        Game game = games.findById(gameId).orElseThrow();
        OffsetDateTime soon = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
        game.setStartsAt(soon);
        game.setEndsAt(soon.plusHours(1));
        games.saveAndFlush(game);

        gameService.cancelUnfilledGames();

        assertThat(games.findById(gameId).orElseThrow().getStatus()).isEqualTo("cancelled_not_filled");
        assertThat(payments.findByGameId(gameId).getFirst().getStatus()).isEqualTo(Payment.Status.RELEASED);
        assertThat(balance(cardId)).isEqualTo(START);
    }

    /** ATMOS's 1.5% on one split, as the fake takes it. */
    private static final class FakeCommission {
        static long of(long split) {
            return uz.minipolya.minipolya_backend.provider.FakePaymentProvider.commissionOf(split);
        }
    }
}
