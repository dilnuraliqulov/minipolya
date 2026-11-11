package uz.minipolya.minipolya_backend.provider.fake;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R44: a refused card payment says why — INSUFFICIENT_FUNDS, CARD_EXPIRED or CARD_DECLINED —
 * as the {@code code} of a 400 (team games, a friends game share) and as the
 * {@code declineReason} of the booking and the payment (bookings answer 200). The fake's
 * 0051, 0054 and 0002 cards give the three.
 */
@DatabaseTest
class DeclineReasonFlowTest extends FakeProviderFlowSupport {

    private static final String[][] CARDS = {
            {"8600123412340051", "INSUFFICIENT_FUNDS"},
            {"8600123412340054", "CARD_EXPIRED"},
            {"8600123412340002", "CARD_DECLINED"}};

    // ---- bookings ----

    @Test
    void aDeclinedBookingSaysWhyOnTheBookingAndOnThePayment() throws Exception {
        User player = data.user("Decline Booking Player");
        int hour = 9;
        for (String[] card : CARDS) {
            UUID cardId = addCard(player, card[0]);

            UUID bookingId = bookingId(book(player, cardId, "FULL", slot(4, hour++))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.paymentStatus").value("unpaid"))
                    .andExpect(jsonPath("$.data.declineReason").value(card[1])));

            mvc.perform(get("/api/bookings/" + bookingId).header("Authorization", data.bearer(player)))
                    .andExpect(jsonPath("$.data.declineReason").value(card[1]));
            assertThat(onlyPayment(bookingId, Payment.PaymentType.BOOKING).getDeclineReason().name()).isEqualTo(card[1]);
        }
        String mine = mvc.perform(get("/api/payments/my").header("Authorization", data.bearer(player)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(mine).contains("\"declineReason\":\"INSUFFICIENT_FUNDS\"")
                .contains("\"declineReason\":\"CARD_EXPIRED\"")
                .contains("\"declineReason\":\"CARD_DECLINED\"")
                // Never the provider's own code.
                .doesNotContain("STPIMS");
    }

    @Test
    void aDeclinedRemainderSaysWhyUntilALaterPaymentGoesThrough() throws Exception {
        User player = data.user("Decline Remainder Player");
        UUID good = addCard(player, "8600123412341234");
        UUID empty = addCard(player, "8600123412340051");
        UUID bookingId = bookingId(book(player, good, "MINIMUM", slot(4, 15)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.declineReason").doesNotExist()));

        payRemainder(player, bookingId, empty)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("partially_paid"))
                .andExpect(jsonPath("$.data.declineReason").value("INSUFFICIENT_FUNDS"));

        payRemainder(player, bookingId, good)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentStatus").value("paid"))
                .andExpect(jsonPath("$.data.declineReason").doesNotExist());
    }

    private org.springframework.test.web.servlet.ResultActions payRemainder(User player, UUID bookingId, UUID cardId)
            throws Exception {
        return mvc.perform(post("/api/bookings/" + bookingId + "/pay-remainder").header("Authorization", data.bearer(player))
                .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"" + cardId + "\"}"));
    }

    // ---- team games: a 400 with the reason as its code ----

    @Test
    void creatingATeamGameWithAnEmptyCardIs400InsufficientFunds() throws Exception {
        User leader = data.user("Decline Creator");
        UUID empty = addCard(leader, "8600123412340051");

        mvc.perform(post("/api/games").header("Authorization", data.bearer(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stadiumId\":\"" + stadium.getId() + "\",\"type\":\"team_vs_team\",\"maxPlayers\":10,"
                                + "\"startsAt\":\"" + slot(3, 18) + "\",\"teamAId\":\"" + team(leader, "Empty A")
                                + "\",\"cardId\":\"" + empty + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.message").value("Card payment was declined; the game was not created"));
    }

    @Test
    void joiningWithAnExpiredCardIs400CardExpiredAndTheGameWaitsOn() throws Exception {
        User leaderA = data.user("Decline Join A");
        User leaderB = data.user("Decline Join B");
        UUID gameId = createTeamGame(leaderA, team(leaderA, "Join A"), addCard(leaderA, "8600123412341111"), slot(3, 19));
        UUID expired = addCard(leaderB, "8600123412340054");

        join(leaderB, gameId, team(leaderB, "Join B"), expired)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CARD_EXPIRED"))
                .andExpect(jsonPath("$.message").value("Card payment was declined; the game is still looking for an opponent"));

        assertThat(games.findById(gameId).orElseThrow().getStatus()).isEqualTo("open");
    }

    @Test
    void aTeamRemainderDeclinedIs400CardDeclined() throws Exception {
        User leaderA = data.user("Decline Rem A");
        User leaderB = data.user("Decline Rem B");
        UUID gameId = createTeamGame(leaderA, team(leaderA, "Rem A"), addCard(leaderA, "8600123412341111"), slot(3, 20));
        join(leaderB, gameId, team(leaderB, "Rem B"), addCard(leaderB, "8600123412342222")).andExpect(status().isOk());
        UUID declining = addCard(leaderB, "8600123412340002");

        mvc.perform(post("/api/games/" + gameId + "/pay-remainder").header("Authorization", data.bearer(leaderB))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"" + declining + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CARD_DECLINED"))
                .andExpect(jsonPath("$.message").value("Card payment was declined; the remainder is still owed"));
    }
}
