package uz.minipolya.minipolya_backend.controller.admin;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.QueryCounter;
import uz.minipolya.minipolya_backend.support.TestData;
import uz.minipolya.minipolya_backend.util.TashkentDays;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R16 + R6: payments for the admin panel, and one revenue calculation behind every figure. */
@DatabaseTest
class AdminPaymentControllerTest {

    private static final OffsetDateTime BASE = OffsetDateTime.parse("2030-03-04T05:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private QueryCounter queries;
    @Autowired
    private Clock clock;

    private String adminToken;
    private User owner;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        adminToken = data.bearer(data.admin("Payments Admin"));
        owner = data.owner("Payments Owner");
        stadium = data.stadium(owner, "Arena R16");
    }

    private Payment gamePayment(Game game, User payer, long amount, Payment.Status status) {
        return data.save(Payment.builder()
                .game(game).user(payer)
                .amount(amount).ownerAmount(amount - 2_543_857).platformAmount(2_543_857L)
                .cardFeeExpected(120_000L).commissionPercent(0)
                .method(Payment.Method.CARD).paymentType(Payment.PaymentType.TEAM_GAME)
                .provider(Payment.Provider.ATMOS).providerTxnId("hold-" + UUID.randomUUID())
                .status(status).build());
    }

    private Payment refundOf(Payment original, Payment.Status status) {
        return data.save(Payment.builder()
                .booking(original.getBooking()).game(original.getGame()).user(original.getUser())
                .amount(-original.getAmount()).ownerAmount(-original.getOwnerAmount())
                .platformAmount(-original.getPlatformAmount())
                .cardFeeExpected(-original.getCardFeeExpected()).commissionPercent(0)
                .method(Payment.Method.CARD).paymentType(Payment.PaymentType.REFUND)
                .provider(Payment.Provider.ATMOS).providerTxnId(original.getProviderTxnId() + "-refund")
                .status(status).refundReason("Cancelled").refundOfPayment(original).build());
    }

    // ---- Access ----

    @Test
    void withoutATokenItIs401AndANonAdminGets403() throws Exception {
        mvc.perform(get("/api/admin/payments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/payments/summary")).andExpect(status().isUnauthorized());
        String player = data.bearer(data.user("Payments Player"));
        mvc.perform(get("/api/admin/payments").header("Authorization", player)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments/summary").header("Authorization", player))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments/" + UUID.randomUUID()).header("Authorization", player))
                .andExpect(status().isForbidden());
    }

    // ---- List and detail ----

    @Test
    void aPageOfPaymentsCostsAFixedNumberOfQueries() throws Exception {
        User player = data.user("Payer Many");
        for (int i = 0; i < 13; i++) {
            Booking booking = data.booking(stadium, player, BASE.plusHours(i), "confirmed");
            data.cardPayment(booking, 20_310_000L, 20_304_569L, 310_000L, 304_650L, Payment.Status.PAID);
        }
        for (int i = 0; i < 12; i++) {
            gamePayment(data.teamGame(stadium, player, BASE.plusDays(1).plusHours(i)), player,
                    7_620_000L, Payment.Status.CAPTURED);
        }
        queries.start();

        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken)
                        .param("stadiumId", stadium.getId().toString()).param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(25)))
                .andExpect(jsonPath("$.data.content[0].stadiumName").value("Arena R16"));

        // auth user, payments (+payer +booking/game +stadiums), count
        assertThat(queries.count()).isLessThanOrEqualTo(4);
    }

    @Test
    void aPaymentShowsItsSplitAndFees() throws Exception {
        User player = data.user("Aziza Rahimova");
        Booking booking = data.booking(stadium, player, BASE, "confirmed");
        Payment paid = data.cardPayment(booking, 20_310_000L, 20_304_569L, 310_000L, 304_650L, Payment.Status.PAID);

        mvc.perform(get("/api/admin/payments/" + paid.getId()).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userName").value("Aziza Rahimova"))
                .andExpect(jsonPath("$.data.userPhone").value(player.getPhone()))
                .andExpect(jsonPath("$.data.paymentType").value("booking"))
                .andExpect(jsonPath("$.data.bookingId").value(booking.getId().toString()))
                .andExpect(jsonPath("$.data.stadiumId").value(stadium.getId().toString()))
                .andExpect(jsonPath("$.data.amount").value(20_310_000))
                .andExpect(jsonPath("$.data.ownerAmount").value(20_304_569))
                .andExpect(jsonPath("$.data.platformAmount").value(5_431))
                .andExpect(jsonPath("$.data.cardFee").value(310_000))
                .andExpect(jsonPath("$.data.providerCommission").value(304_650))
                .andExpect(jsonPath("$.data.commissionPercent").value(0))
                .andExpect(jsonPath("$.data.method").value("card"))
                .andExpect(jsonPath("$.data.provider").value("atmos"))
                .andExpect(jsonPath("$.data.status").value("paid"))
                .andExpect(jsonPath("$.data.gameId").doesNotExist())
                .andExpect(jsonPath("$.data.refundOfPaymentId").doesNotExist())
                .andExpect(jsonPath("$.data.linkedPayments", hasSize(0)));
    }

    @Test
    void aRefundAndItsOriginalLinkToEachOther() throws Exception {
        Booking booking = data.booking(stadium, data.user("Refunded Player"), BASE, "cancelled_by_user");
        Payment original = data.cardPayment(booking, 10_160_000L, 10_152_285L, 160_000L, 152_400L,
                Payment.Status.REFUNDED);
        Payment refund = refundOf(original, Payment.Status.REFUNDED);

        mvc.perform(get("/api/admin/payments/" + original.getId()).header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.linkedPayments", hasSize(1)))
                .andExpect(jsonPath("$.data.linkedPayments[0].id").value(refund.getId().toString()))
                .andExpect(jsonPath("$.data.linkedPayments[0].amount").value(-10_160_000));

        mvc.perform(get("/api/admin/payments/" + refund.getId()).header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.paymentType").value("refund"))
                .andExpect(jsonPath("$.data.amount").value(-10_160_000))
                .andExpect(jsonPath("$.data.refundReason").value("Cancelled"))
                .andExpect(jsonPath("$.data.refundOfPaymentId").value(original.getId().toString()))
                .andExpect(jsonPath("$.data.linkedPayments[0].id").value(original.getId().toString()));
    }

    @Test
    void refundsListFailedOnesFirst() throws Exception {
        Booking booking = data.booking(stadium, data.user("Refund Order"), BASE, "cancelled_by_owner");
        Payment a = data.cardPayment(booking, 1_020_000L, 1_015_229L, 20_000L, null, Payment.Status.REFUNDED);
        Payment b = data.cardPayment(booking, 2_040_000L, 2_030_458L, 40_000L, null, Payment.Status.PAID);
        Payment c = data.cardPayment(booking, 3_050_000L, 3_045_686L, 50_000L, null, Payment.Status.REFUNDED);
        refundOf(a, Payment.Status.REFUNDED);
        Payment failed = refundOf(b, Payment.Status.FAILED);
        Payment newest = refundOf(c, Payment.Status.REFUNDED);

        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken)
                        .param("type", "refund").param("stadiumId", stadium.getId().toString()))
                .andExpect(jsonPath("$.data.content", hasSize(3)))
                .andExpect(jsonPath("$.data.content[0].id").value(failed.getId().toString()))
                .andExpect(jsonPath("$.data.content[1].id").value(newest.getId().toString()));
    }

    @Test
    void filtersAndSearch() throws Exception {
        User player = data.user("Bekzod Search");
        Booking booking = data.booking(stadium, player, BASE, "confirmed");
        Payment card = data.cardPayment(booking, 20_310_000L, 20_304_569L, 310_000L, null, Payment.Status.PAID);
        data.cashPayment(booking, 5_000_000L);
        data.cardPayment(data.booking(data.stadium(owner, "Elsewhere"), data.user("Other"), BASE, "confirmed"),
                20_310_000L, 20_304_569L, 310_000L, null, Payment.Status.PAID);

        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken)
                        .param("stadiumId", stadium.getId().toString()).param("method", "cash"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].method").value("cash"))
                .andExpect(jsonPath("$.data.content[0].providerTxnId").doesNotExist());
        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken).param("search", "BEKZOD"))
                .andExpect(jsonPath("$.data.content", hasSize(1)));
        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken)
                        .param("search", card.getProviderTxnId()))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].id").value(card.getId().toString()));
        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken)
                        .param("stadiumId", stadium.getId().toString()).param("status", "paid")
                        .param("type", "booking").param("method", "card"))
                .andExpect(jsonPath("$.data.content", hasSize(1)));
    }

    @Test
    void badInputIs400AndAnUnknownPaymentIs404() throws Exception {
        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken).param("status", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid status"));
        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken).param("type", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid type"));
        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken).param("method", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid method"));
        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken)
                        .param("from", "2030-01-02").param("to", "2030-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to must be on or after from"));
        mvc.perform(get("/api/admin/payments/summary").header("Authorization", adminToken).param("from", "2030-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("from and to are required"));
        mvc.perform(get("/api/admin/payments/" + UUID.randomUUID()).header("Authorization", adminToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Payment not found"));
        mvc.perform(get("/api/admin/payments/xyz").header("Authorization", adminToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid id"));
    }

    // ---- Summary and the overview ----

    @Test
    void theSummaryCountsCardMoneyKeptAndAddsUp() throws Exception {
        User player = data.user("Summary Player");
        Booking booking = data.booking(stadium, player, BASE, "confirmed");
        // kept: 20 310 000 with ATMOS's reported 304 650
        data.cardPayment(booking, 20_310_000L, 20_304_569L, 310_000L, 304_650L, Payment.Status.PAID);
        // paid then reversed: nets to zero
        Payment reversed = data.cardPayment(booking, 10_160_000L, 10_152_285L, 160_000L, 152_400L,
                Payment.Status.REFUNDED);
        refundOf(reversed, Payment.Status.REFUNDED);
        // a captured hold with no commission reported: 1.5% estimated = 114 300
        Game game = data.teamGame(stadium, player, BASE.plusDays(1));
        gamePayment(game, player, 7_620_000L, Payment.Status.CAPTURED);
        // not money kept: cash, a hold still on the card, a declined card
        data.cashPayment(booking, 5_000_000L);
        gamePayment(data.teamGame(stadium, player, BASE.plusDays(2)), player, 7_620_000L, Payment.Status.HELD);
        data.cardPayment(booking, 1_020_000L, 1_015_229L, 20_000L, null, Payment.Status.FAILED);

        LocalDate today = TashkentDays.today(clock);
        String body = mvc.perform(get("/api/admin/payments/summary").header("Authorization", adminToken)
                        .param("from", today.toString()).param("to", today.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appRevenue").value(20_310_000 + 7_620_000))
                .andExpect(jsonPath("$.data.cardFees").value(304_650 + 114_300))
                .andExpect(jsonPath("$.data.refunded").value(10_160_000))
                .andExpect(jsonPath("$.data.held").value(7_620_000))
                .andReturn().getResponse().getContentAsString();

        long app = ((Number) JsonPath.read(body, "$.data.appRevenue")).longValue();
        long owners = ((Number) JsonPath.read(body, "$.data.ownerPayouts")).longValue();
        long platform = ((Number) JsonPath.read(body, "$.data.platformRevenue")).longValue();
        long fees = ((Number) JsonPath.read(body, "$.data.cardFees")).longValue();
        assertThat(owners + platform + fees).isEqualTo(app);

        // The overview reads the same calculation: this month and today agree with the summary.
        String stats = mvc.perform(get("/api/admin/stats").header("Authorization", adminToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(stats, "$.data.todayRevenue")).longValue()).isEqualTo(app);
        assertThat(((Number) JsonPath.read(stats, "$.data.monthRevenue")).longValue()).isEqualTo(app);
        assertThat(((Number) JsonPath.read(stats, "$.data.totalRevenue")).longValue()).isEqualTo(app);

        String chart = mvc.perform(get("/api/admin/stats/revenue").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.dailyRevenue", hasSize(30)))
                .andReturn().getResponse().getContentAsString();
        assertThat(((Number) JsonPath.read(chart, "$.data.dailyRevenue[29].revenue")).longValue()).isEqualTo(app);
        assertThat((String) JsonPath.read(chart, "$.data.dailyRevenue[29].date")).isEqualTo(today.toString());
    }
}
