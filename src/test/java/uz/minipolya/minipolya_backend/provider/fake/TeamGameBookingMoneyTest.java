package uz.minipolya.minipolya_backend.provider.fake;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.service.CardPricing;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A team game counted in player terms (both teams chose minimum / full): its money lives on
 * the teams' payments ({@code payments.game_team_id}), never on the booking, so the
 * booking-level figures — owner schedule, unpaid list, admin booking, owner stats — are
 * derived from those payments. Side A pays in full (130 000), side B the minimum (55 000,
 * 75 000 left).
 */
@DatabaseTest
class TeamGameBookingMoneyTest extends FakeProviderFlowSupport {

    private static final long MINIMUM = 5_500_000L;
    private static final long FULL = 13_000_000L;
    private static final long REST = 7_500_000L;

    @Autowired
    private CardPricing cardPricing;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    private UUID confirmedGame(int hour) throws Exception {
        User a = data.user("BM Leader A " + hour);
        User b = data.user("BM Leader B " + hour);
        UUID cardA = addCard(a, "8600123412345" + (200 + hour));
        UUID cardB = addCard(b, "9860123412345" + (200 + hour));
        String body = mvc.perform(post("/api/games").header("Authorization", data.bearer(a))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stadiumId\":\"" + stadium.getId() + "\",\"type\":\"team_vs_team\",\"maxPlayers\":10,"
                                + "\"startsAt\":\"" + slot(3, hour) + "\",\"teamAId\":\"" + team(a, "BM A " + hour)
                                + "\",\"cardId\":\"" + cardA + "\",\"paymentOption\":\"FULL\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID gameId = UUID.fromString(JsonPath.read(body, "$.data.id"));
        mvc.perform(post("/api/games/" + gameId + "/join").header("Authorization", data.bearer(b))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"gameId\":\"" + gameId + "\",\"teamId\":\"" + team(b, "BM B " + hour)
                                + "\",\"cardId\":\"" + cardB + "\",\"paymentOption\":\"MINIMUM\"}"))
                .andExpect(status().isOk());
        return gameId;
    }

    private UUID bookingIdOf(UUID gameId) {
        return games.findById(gameId).orElseThrow().getBookingId();
    }

    /** The game's booking as the owner's schedule shows it. */
    private Map<String, Object> scheduled(UUID bookingId) throws Exception {
        LocalDate day = slot(3, 10).atZoneSameInstant(TASHKENT).toLocalDate();
        String body = mvc.perform(get("/api/owner/dashboard/stadiums/" + stadium.getId() + "/schedule")
                        .param("from", day.toString()).param("to", day.toString())
                        .header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> found = JsonPath.read(body, "$.data[*].bookings[?(@.id == '" + bookingId + "')]");
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    private Map<String, Object> adminBooking(UUID bookingId) throws Exception {
        String body = mvc.perform(get("/api/admin/bookings/" + bookingId)
                        .header("Authorization", data.bearer(data.admin("BM Admin " + UUID.randomUUID()))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data");
    }

    private Map<String, Object> stats() throws Exception {
        String body = mvc.perform(get("/api/owner/dashboard/stadiums/" + stadium.getId() + "/stats")
                        .header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data");
    }

    private List<Map<String, Object>> unpaid() throws Exception {
        String body = mvc.perform(get("/api/owner/dashboard/stadiums/" + stadium.getId() + "/bookings")
                        .param("unpaid", "true").header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.content");
    }

    private List<Payment> cardPayments(UUID gameId) {
        return payments.findByGameId(gameId).stream()
                .filter(p -> p.getMethod() != Payment.Method.CASH)
                .filter(p -> p.getAmount() > 0)
                .toList();
    }

    /** What the owner keeps of the game's card money, after ATMOS. */
    private long ownerReceivesOfCards(UUID gameId) {
        return cardPayments(gameId).stream().mapToLong(p -> cardPricing.ownerReceives(p.getOwnerAmount())).sum();
    }

    private static long num(Object value) {
        return ((Number) value).longValue();
    }

    @Test
    void theScheduleCountsTheTeamsCardMoneyAndThenTheirCash() throws Exception {
        UUID gameId = confirmedGame(10);
        UUID bookingId = bookingIdOf(gameId);
        assertThat(cardPayments(gameId)).hasSize(2)
                .allSatisfy(p -> assertThat(p.getStatus()).isIn(Payment.Status.PAID, Payment.Status.CAPTURED));
        long cardOwner = ownerReceivesOfCards(gameId);
        assertThat(cardOwner).isPositive();

        Map<String, Object> b = scheduled(bookingId);
        assertThat(num(b.get("price"))).isEqualTo(FULL + FULL);
        assertThat(num(b.get("paidAmount"))).isEqualTo(FULL + MINIMUM);
        assertThat(num(b.get("remainingAmount"))).isEqualTo(REST);
        assertThat(num(b.get("paidInApp"))).isEqualTo(FULL + MINIMUM);
        assertThat(num(b.get("paidInCash"))).isZero();
        assertThat(num(b.get("ownerReceives"))).isEqualTo(cardOwner);
        assertThat(b).doesNotContainKey("remainderMark");

        mvc.perform(post("/api/games/" + gameId + "/cash").header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"side\":\"b\"}"))
                .andExpect(status().isOk());

        b = scheduled(bookingId);
        assertThat(num(b.get("paidAmount"))).isEqualTo(FULL + FULL);
        assertThat(num(b.get("remainingAmount"))).isZero();
        assertThat(b.get("paymentStatus")).isEqualTo("paid");
        assertThat(num(b.get("paidInApp"))).isEqualTo(FULL + MINIMUM);
        assertThat(num(b.get("paidInCash"))).isEqualTo(REST);
        assertThat(num(b.get("ownerReceives"))).isEqualTo(cardOwner + REST);
        assertThat(b).doesNotContainKey("remainderMark");

        // Undoing the cash voids the row: it counts nowhere again.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/games/" + gameId + "/remainder-mark").param("side", "b")
                        .header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk());
        b = scheduled(bookingId);
        assertThat(num(b.get("paidInCash"))).isZero();
        assertThat(num(b.get("ownerReceives"))).isEqualTo(cardOwner);
        assertThat(num(b.get("remainingAmount"))).isEqualTo(REST);
    }

    @Test
    void aLegacyNetGameBookingKeepsItsOwnFigures() throws Exception {
        UUID gameId = confirmedGame(13);
        UUID bookingId = bookingIdOf(gameId);
        // As a game with a team from before changeset 052 is counted: owner's terms, net.
        entityManager.flush();
        jdbc.update("UPDATE bookings SET pricing_model = 'net', paid_amount = 0 WHERE id = ?", bookingId);
        entityManager.clear();

        Map<String, Object> b = scheduled(bookingId);
        assertThat(num(b.get("paidInApp"))).isZero();
        assertThat(num(b.get("ownerReceives"))).isZero();
    }

    @Test
    void theAdminBookingCountsTheTeamsMoneyAndCash() throws Exception {
        UUID gameId = confirmedGame(11);
        UUID bookingId = bookingIdOf(gameId);
        long cardOwner = ownerReceivesOfCards(gameId);
        long atmos = cardPayments(gameId).stream().mapToLong(cardPricing::atmosFee).sum();

        Map<String, Object> b = adminBooking(bookingId);
        assertThat(num(b.get("paidInApp"))).isEqualTo(FULL + MINIMUM);
        assertThat(num(b.get("ownerReceives"))).isEqualTo(cardOwner);
        assertThat(num(b.get("atmosFee"))).isEqualTo(atmos).isPositive();
        assertThat(num(b.get("remainingAmount"))).isEqualTo(REST);
        assertThat(b).doesNotContainKeys("cashReceived", "remainderMark");

        mvc.perform(post("/api/games/" + gameId + "/cash").header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"side\":\"b\"}"))
                .andExpect(status().isOk());
        b = adminBooking(bookingId);
        assertThat(num(b.get("cashReceived"))).isEqualTo(REST);
        assertThat(b.get("cashReceivedAt")).isNotNull();
        assertThat(num(b.get("paidInCash"))).isEqualTo(REST);
        assertThat(num(b.get("ownerReceives"))).isEqualTo(cardOwner + REST);
        assertThat(num(b.get("remainingAmount"))).isZero();
    }

    @Test
    void theOwnerStatsAndTheUnpaidListAgree() throws Exception {
        UUID gameId = confirmedGame(12);
        UUID bookingId = bookingIdOf(gameId);
        OffsetDateTime startsAt = slot(3, 12);
        boolean thisMonth = startsAt.atZoneSameInstant(TASHKENT).getMonth() == LocalDate.now(TASHKENT).getMonth();

        List<Map<String, Object>> owed = unpaid();
        assertThat(owed).extracting(m -> m.get("id")).contains(bookingId.toString());
        Map<String, Object> row = owed.stream().filter(m -> bookingId.toString().equals(m.get("id"))).findFirst().orElseThrow();
        assertThat(num(row.get("remainingAmount"))).isEqualTo(REST);
        assertThat(num(row.get("paidInApp"))).isEqualTo(FULL + MINIMUM);

        Map<String, Object> s = stats();
        assertThat(num(s.get("todayPaidInApp"))).isEqualTo(FULL + MINIMUM);
        assertThat(num(s.get("monthPaidInApp"))).isEqualTo(FULL + MINIMUM);
        assertThat(num(s.get("todayPaidInCash"))).isZero();
        if (thisMonth) {
            assertThat(num(s.get("monthOutstanding"))).isEqualTo(REST);
        }

        mvc.perform(post("/api/games/" + gameId + "/cash").header("Authorization", data.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"side\":\"b\"}"))
                .andExpect(status().isOk());
        entityManager.flush();

        assertThat(unpaid()).extracting(m -> m.get("id")).doesNotContain(bookingId.toString());
        s = stats();
        assertThat(num(s.get("todayPaidInApp"))).isEqualTo(FULL + MINIMUM);
        assertThat(num(s.get("todayPaidInCash"))).isEqualTo(REST);
        assertThat(num(s.get("monthOutstanding"))).isZero();
    }
}
