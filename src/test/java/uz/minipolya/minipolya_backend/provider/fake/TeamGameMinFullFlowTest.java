package uz.minipolya.minipolya_backend.provider.fake;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Team vs team, minimum / full payment per team (T4-T10), end to end through the fake
 * provider. The stadium: P = 200 000 so'm, M = 50 000, fee 25 000, card fee 1.5 %, step
 * 5 000 — the spec's example: minimum 55 000 (75 000 later), full 130 000.
 */
@DatabaseTest
class TeamGameMinFullFlowTest extends FakeProviderFlowSupport {

    private static final long MINIMUM = 5_500_000L;
    private static final long FULL = 13_000_000L;
    private static final long REST = 7_500_000L;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private record Game2(User leaderA, User leaderB, UUID cardA, UUID cardB, UUID gameId) {
    }

    private UUID create(User leader, UUID cardId, OffsetDateTime startsAt, String option) throws Exception {
        String opt = option == null ? "" : ",\"paymentOption\":\"" + option + "\"";
        String body = mvc.perform(post("/api/games").header("Authorization", data.bearer(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stadiumId\":\"" + stadium.getId() + "\",\"type\":\"team_vs_team\",\"maxPlayers\":10,"
                                + "\"startsAt\":\"" + startsAt + "\",\"teamAId\":\"" + team(leader, "MF A " + startsAt.getHour())
                                + "\",\"cardId\":\"" + cardId + "\"" + opt + "}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    private ResultActions joinWith(User leader, UUID gameId, UUID cardId, String option) throws Exception {
        String opt = option == null ? "" : ",\"paymentOption\":\"" + option + "\"";
        return mvc.perform(post("/api/games/" + gameId + "/join").header("Authorization", data.bearer(leader))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gameId\":\"" + gameId + "\",\"teamId\":\"" + team(leader, "MF B " + gameId)
                        + "\",\"cardId\":\"" + cardId + "\"" + opt + "}"));
    }

    private Game2 confirmed(int hour, String optionA, String optionB) throws Exception {
        User a = data.user("MF Leader A " + hour);
        User b = data.user("MF Leader B " + hour);
        UUID cardA = addCard(a, "8600123412345" + (100 + hour));
        UUID cardB = addCard(b, "9860123412345" + (100 + hour));
        UUID gameId = create(a, cardA, slot(3, hour), optionA);
        joinWith(b, gameId, cardB, optionB).andExpect(status().isOk());
        return new Game2(a, b, cardA, cardB, gameId);
    }

    /** One team of the game as the viewer sees it. */
    private Map<String, Object> team(UUID gameId, User viewer, String side) throws Exception {
        String body = mvc.perform(get("/api/games/" + gameId).header("Authorization", data.bearer(viewer)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> teams = JsonPath.read(body, "$.data.teams[?(@.side == '" + side + "')]");
        return teams.getFirst();
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    private ResultActions ownerCall(String method, UUID gameId, String path, String side, User who) throws Exception {
        var request = "DELETE".equals(method)
                ? delete("/api/games/" + gameId + path).param("side", side)
                : post("/api/games/" + gameId + path).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"side\":\"" + side + "\"}");
        return mvc.perform(request.header("Authorization", data.bearer(who)));
    }

    private Booking bookingOf(UUID gameId) {
        return bookings.findById(games.findById(gameId).orElseThrow().getBookingId()).orElseThrow();
    }

    private List<String> auditActions(UUID gameId) {
        entityManager.flush();
        return jdbc.queryForList("SELECT action FROM audit_log WHERE entity_id = ?", String.class, gameId);
    }

    // ---- quotes (T7, T8) ----

    @Test
    void theCreateQuoteIsTheSpecExample() throws Exception {
        mvc.perform(get("/api/stadiums/" + stadium.getId() + "/team-game-quote").param("startsAt", slot(3, 10).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options.length()").value(2))
                .andExpect(jsonPath("$.data.options[0].option").value("minimum"))
                .andExpect(jsonPath("$.data.options[0].amount").value(MINIMUM))
                .andExpect(jsonPath("$.data.options[0].remainderAmount").value(REST))
                .andExpect(jsonPath("$.data.options[1].option").value("full"))
                .andExpect(jsonPath("$.data.options[1].amount").value(FULL))
                .andExpect(jsonPath("$.data.options[1].remainderAmount").value(0))
                .andExpect(jsonPath("$.data.cancellationDeadline").isString());
    }

    @Test
    void theCreateQuoteRefusesAnHourThatCannotBeBooked() throws Exception {
        mvc.perform(get("/api/stadiums/" + stadium.getId() + "/team-game-quote")
                        .param("startsAt", slot(3, 10).plusMinutes(30).toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stadiums/" + stadium.getId() + "/team-game-quote")
                        .param("startsAt", slot(-1, 10).toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/stadiums/" + UUID.randomUUID() + "/team-game-quote").param("startsAt", slot(3, 10).toString()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aMinimumAboveTheHalfOffersOnlyFull() throws Exception {
        stadium.setMinBookingAmount(15_000_000L); // above P/2 + F = 12 500 000
        stadiums.saveAndFlush(stadium);
        mvc.perform(get("/api/stadiums/" + stadium.getId() + "/team-game-quote").param("startsAt", slot(3, 10).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options.length()").value(1))
                .andExpect(jsonPath("$.data.options[0].option").value("full"))
                .andExpect(jsonPath("$.data.options[0].amount").value(FULL));
    }

    @Test
    void theJoinQuoteIsTheFreeSidesOptions() throws Exception {
        User a = data.user("MF Quote A");
        UUID gameId = create(a, addCard(a, "8600123412349001"), slot(3, 11), null);
        User viewer = data.user("MF Quote Viewer");

        mvc.perform(get("/api/games/" + gameId + "/join-quote").header("Authorization", data.bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.options[0].amount").value(MINIMUM))
                .andExpect(jsonPath("$.data.options[0].remainderAmount").value(REST))
                .andExpect(jsonPath("$.data.options[1].amount").value(FULL));
        mvc.perform(get("/api/games/" + UUID.randomUUID() + "/join-quote").header("Authorization", data.bearer(viewer)))
                .andExpect(status().isNotFound());
        // T8: the board's card agrees.
        String card = "$.data.content[?(@.gameId == '" + gameId + "')].";
        mvc.perform(get("/api/games/challenges").header("Authorization", data.bearer(viewer)))
                .andExpect(jsonPath(card + "amountToPayNow").value(contains((int) MINIMUM)))
                .andExpect(jsonPath(card + "remainderAmount").value(contains((int) REST)))
                .andExpect(jsonPath(card + "fullAmount").value(contains((int) FULL)));
    }

    // ---- paying (T4, T6) ----

    @Test
    void minimumHoldsAndChargesFiftyFiveThousandAndThePlatformNetsItsFee() throws Exception {
        Game2 g = confirmed(12, null, "MINIMUM");

        List<Payment> paid = payments.findByGameId(g.gameId());
        assertThat(paid).hasSize(2).allSatisfy(p -> {
            assertThat(p.getAmount()).isEqualTo(MINIMUM);
            assertThat(PriceCalculator.netAfterFee(p.getPlatformAmount(), 150)).isEqualTo(2_500_000L);
        });
        assertThat(balance(g.cardA())).isEqualTo(START - MINIMUM);
        assertThat(balance(g.cardB())).isEqualTo(START - MINIMUM);

        Map<String, Object> a = team(g.gameId(), g.leaderA(), "a");
        assertThat(a.get("paymentOption")).isEqualTo("minimum");
        assertThat(num(a.get("price"))).isEqualTo(MINIMUM);
        assertThat(num(a.get("paidAmount"))).isEqualTo(MINIMUM);
        assertThat(num(a.get("remainderAmount"))).isEqualTo(REST);
        assertThat(num(a.get("remainingAmount"))).isEqualTo(REST);
        assertThat(a).doesNotContainKeys("platformFee", "ownerPart", "ownerAdvance", "remainderMark");
    }

    @Test
    void mixedOptionsAndTheRestPaidInTheAppPerTeam() throws Exception {
        Game2 g = confirmed(13, "FULL", "MINIMUM");
        assertThat(num(team(g.gameId(), g.leaderA(), "a").get("remainingAmount"))).isZero();
        assertThat(bookingOf(g.gameId()).getRemainingAmount()).isEqualTo(REST);

        // Side A paid in full: nothing left to pay.
        mvc.perform(post("/api/games/" + g.gameId() + "/pay-remainder").header("Authorization", data.bearer(g.leaderA()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"" + g.cardA() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Already paid"));
        // Side B pays its 75 000: all the owner's.
        mvc.perform(post("/api/games/" + g.gameId() + "/pay-remainder").header("Authorization", data.bearer(g.leaderB()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"" + g.cardB() + "\"}"))
                .andExpect(status().isOk());
        Payment rest = payments.findByGameId(g.gameId()).stream()
                .filter(p -> p.getPaymentType() == Payment.PaymentType.REMAINDER).findFirst().orElseThrow();
        assertThat(rest.getAmount()).isEqualTo(REST);
        assertThat(rest.getPlatformAmount()).isZero();
        assertThat(balance(g.cardB())).isEqualTo(START - FULL);

        Map<String, Object> b = team(g.gameId(), g.leaderB(), "b");
        assertThat(num(b.get("paidAmount"))).isEqualTo(FULL);
        assertThat(num(b.get("remainingAmount"))).isZero();
        Booking booking = bookingOf(g.gameId());
        assertThat(booking.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.PAID);
        assertThat(booking.getPaidAmount()).isEqualTo(2 * FULL);
    }

    // ---- the owner's marks (T10) ----

    @Test
    void cashSetsTheMarkAndTheOwnerUndoesItTheSameDay() throws Exception {
        Game2 g = confirmed(14, null, null);

        ownerCall("POST", g.gameId(), "/cash", "b", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cash recorded"));
        Map<String, Object> b = team(g.gameId(), owner, "b");
        assertThat(b.get("remainderMark")).isEqualTo("cash_received");
        assertThat(b.get("remainderMarkUndoableUntil")).isNotNull();
        assertThat(num(b.get("remainingAmount"))).isZero();
        assertThat(num(b.get("paidAmount"))).isEqualTo(FULL);
        // A player sees the mark, not the owner's undo deadline.
        assertThat(team(g.gameId(), g.leaderB(), "b")).doesNotContainKey("remainderMarkUndoableUntil");
        Payment cash = payments.findByGameId(g.gameId()).stream()
                .filter(p -> p.getMethod() == Payment.Method.CASH).findFirst().orElseThrow();
        assertThat(cash.getAmount()).isEqualTo(REST);
        assertThat(bookingOf(g.gameId()).getRemainingAmount()).isEqualTo(REST); // side A still owes

        // Nothing left on B: cash and not-paid both 409.
        ownerCall("POST", g.gameId(), "/cash", "b", owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already paid"));
        ownerCall("POST", g.gameId(), "/not-paid", "b", owner).andExpect(status().isConflict());

        ownerCall("DELETE", g.gameId(), "/remainder-mark", "b", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Mark undone"));
        assertThat(payments.findById(cash.getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.CANCELLED);
        b = team(g.gameId(), owner, "b");
        assertThat(b).doesNotContainKey("remainderMark");
        assertThat(num(b.get("remainingAmount"))).isEqualTo(REST);
        assertThat(num(b.get("paidAmount"))).isEqualTo(MINIMUM);
        assertThat(bookingOf(g.gameId()).getRemainingAmount()).isEqualTo(2 * REST);

        ownerCall("DELETE", g.gameId(), "/remainder-mark", "b", owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This team has no remainder mark"));

        assertThat(auditActions(g.gameId())).contains("CASH_RECEIVED", "REMAINDER_MARK_UNDONE");
    }

    @Test
    void notPaidStandsUntilTheTeamPaysInTheApp() throws Exception {
        Game2 g = confirmed(15, null, null);

        ownerCall("POST", g.gameId(), "/not-paid", "a", owner)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Marked as not paid"));
        Map<String, Object> a = team(g.gameId(), g.leaderA(), "a");
        assertThat(a.get("remainderMark")).isEqualTo("not_paid");
        assertThat(num(a.get("remainingAmount"))).isEqualTo(REST);

        mvc.perform(post("/api/games/" + g.gameId() + "/pay-remainder").header("Authorization", data.bearer(g.leaderA()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cardId\":\"" + g.cardA() + "\"}"))
                .andExpect(status().isOk());
        a = team(g.gameId(), g.leaderA(), "a");
        assertThat(a).doesNotContainKey("remainderMark");
        assertThat(num(a.get("remainingAmount"))).isZero();

        assertThat(auditActions(g.gameId())).contains("REMAINDER_NOT_PAID", "GAME_REMAINDER_PAID");
    }

    @Test
    void afterTheDayOnlyAnAdminUndoesTheMark() throws Exception {
        Game2 g = confirmed(16, null, null);
        ownerCall("POST", g.gameId(), "/cash", "a", owner).andExpect(status().isOk());
        entityManager.flush();
        jdbc.update("UPDATE game_teams SET remainder_marked_at = now() - interval '2 days' WHERE game_id = ? AND side = 'a'",
                g.gameId());
        entityManager.clear();

        ownerCall("DELETE", g.gameId(), "/remainder-mark", "a", owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only an admin can change this mark"));

        User admin = data.admin("MF Admin");
        mvc.perform(delete("/api/admin/games/" + g.gameId() + "/remainder-mark").param("side", "a")
                        .header("Authorization", data.bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Mark undone"))
                .andExpect(jsonPath("$.data.teams[?(@.side == 'a')].remainingAmount").value(contains((int) REST)))
                .andExpect(jsonPath("$.data.teams[0].platformFee").value(2_500_000))
                .andExpect(jsonPath("$.data.teams[0].ownerPart").value(2_500_000));
        assertThat(payments.findByGameId(g.gameId()).stream().filter(p -> p.getMethod() == Payment.Method.CASH))
                .allMatch(p -> p.getStatus() == Payment.Status.CANCELLED);
    }

    @Test
    void whoMayMarkAndWhen() throws Exception {
        Game2 g = confirmed(17, "FULL", null);
        User stranger = data.user("MF Stranger");
        ownerCall("POST", g.gameId(), "/cash", "b", stranger).andExpect(status().isForbidden());
        ownerCall("POST", g.gameId(), "/not-paid", "b", stranger).andExpect(status().isForbidden());
        ownerCall("DELETE", g.gameId(), "/remainder-mark", "b", stranger).andExpect(status().isForbidden());
        // Side A paid in full: nothing to mark.
        ownerCall("POST", g.gameId(), "/not-paid", "a", owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Already paid"));

        // A waiting game: nothing to collect yet.
        User c = data.user("MF Waiting");
        UUID waiting = create(c, addCard(c, "8600123412349002"), slot(3, 18), null);
        ownerCall("POST", waiting, "/cash", "a", owner).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This game is not confirmed yet"));
    }
}
