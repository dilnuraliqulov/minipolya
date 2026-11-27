package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F6 and item 8: a hold whose outcome is unclear is needs_review, shown in the "Ushlab
 * turilgan" tab with the other holds still in play, and settled by an admin by hand.
 */
@DatabaseTest
class AdminPaymentReviewTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private PaymentRepository payments;

    private String adminToken;
    private User leader;
    private GameTeam sideB;
    private Payment review;

    @BeforeEach
    void setUp() {
        adminToken = data.bearer(data.admin("Review Admin"));
        User owner = data.owner("Review Owner");
        Stadium stadium = data.stadium(owner, "Review Arena");
        leader = data.user("Review Leader");
        Game game = data.teamGame(stadium, leader,
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).truncatedTo(ChronoUnit.HOURS));
        sideB = data.gameTeam(game, data.team(leader, "Review Team"), GameParticipant.Side.B);
        review = hold(game, Payment.Status.NEEDS_REVIEW);
    }

    private Payment hold(Game game, Payment.Status status) {
        return data.save(Payment.builder()
                .game(game).gameTeam(sideB).user(leader)
                .amount(5_500_000L).ownerAmount(2_961_928L).platformAmount(2_538_072L)
                .cardFeeExpected(82_500L).commissionPercent(0)
                .method(Payment.Method.CARD).paymentType(Payment.PaymentType.TEAM_GAME)
                .provider(Payment.Provider.ATMOS).providerTxnId("hold-" + UUID.randomUUID())
                .holdExpiresAt(OffsetDateTime.parse("2030-01-02T03:04:05Z"))
                .failureReason(status == Payment.Status.NEEDS_REVIEW ? "Capture unclear" : null)
                .status(status).build());
    }

    private ResultActions resolve(UUID paymentId, String outcome, String reason) throws Exception {
        return mvc.perform(post("/api/admin/payments/" + paymentId + "/resolve-review")
                .header("Authorization", adminToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\":\"" + outcome + "\",\"reason\":\"" + reason + "\"}"));
    }

    @Test
    void theHeldTabListsEveryHoldStillInPlayWithItsSideAndExpiry() throws Exception {
        Game game = review.getGame();
        hold(game, Payment.Status.HELD);
        hold(game, Payment.Status.CAPTURE_PENDING);
        hold(game, Payment.Status.RETURN_PENDING);
        hold(game, Payment.Status.CAPTURED); // settled: not in the tab

        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken)
                        .param("status", "held,capture_pending,return_pending,needs_review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content[*].status").value(
                        containsInAnyOrder("held", "capture_pending", "return_pending", "needs_review")))
                .andExpect(jsonPath("$.data.content[0].teamSide").value("b"));

        mvc.perform(get("/api/admin/payments/" + review.getId()).header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.status").value("needs_review"))
                .andExpect(jsonPath("$.data.failureReason").value("Capture unclear"))
                .andExpect(jsonPath("$.data.teamSide").value("b"))
                .andExpect(jsonPath("$.data.gameTeamId").value(sideB.getId().toString()))
                .andExpect(jsonPath("$.data.holdExpiresAt").exists());
    }

    @Test
    void anUnknownStatusInTheListIs400() throws Exception {
        mvc.perform(get("/api/admin/payments").header("Authorization", adminToken).param("status", "held,nonsense"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid status"));
    }

    @Test
    void anAdminResolvesItAsCapturedOrReleasedWithAReason() throws Exception {
        resolve(review.getId(), "captured", "ATMOS support confirmed the debit")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Payment resolved"))
                .andExpect(jsonPath("$.data.status").value("captured"));

        Payment other = hold(review.getGame(), Payment.Status.NEEDS_REVIEW);
        resolve(other.getId(), "released", "ATMOS support: hold cancelled, no debit")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("released"));
    }

    @Test
    void refundedWritesTheRefundRowMarkedAsReturnedByHand() throws Exception {
        resolve(review.getId(), "refunded", "Debited, then reversed by ATMOS support")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("refunded"))
                .andExpect(jsonPath("$.data.linkedPayments[0].paymentType").value("refund"));

        Payment refund = payments.findByRefundOfPaymentIdOrderByCreatedAtAsc(review.getId()).getFirst();
        assertThat(refund.getAmount()).isEqualTo(-5_500_000L);
        assertThat(refund.isRefundedManually()).isTrue();
        assertThat(refund.getManualRefundReason()).isEqualTo("Debited, then reversed by ATMOS support");
    }

    @Test
    void onlyANeedsReviewPaymentWithAValidOutcomeAndReasonCanBeResolved() throws Exception {
        resolve(review.getId(), "maybe", "a long enough reason")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("outcome must be one of captured, released, refunded"));
        resolve(review.getId(), "released", "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reason must be at least 10 characters"));
        Payment held = hold(review.getGame(), Payment.Status.HELD);
        resolve(held.getId(), "released", "a long enough reason")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a payment that needs review can be resolved"));
    }

    @Test
    void onlyAnAdminMayResolve() throws Exception {
        mvc.perform(post("/api/admin/payments/" + review.getId() + "/resolve-review")
                        .header("Authorization", data.bearer(leader)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"released\",\"reason\":\"a long enough reason\"}"))
                .andExpect(status().isForbidden());
    }
}
