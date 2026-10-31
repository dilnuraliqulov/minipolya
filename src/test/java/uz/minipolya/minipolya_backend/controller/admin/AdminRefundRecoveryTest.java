package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.provider.HoldCancelledException;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.ProviderTimeoutException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.service.BookingService;
import uz.minipolya.minipolya_backend.service.PaymentReconcileService;
import uz.minipolya.minipolya_backend.service.PaymentService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R17 + R8: a refund the provider declines is recorded, never rolled back with the
 * cancellation, counted for the admin, and settled by a retry or by hand.
 */
@DatabaseTest
class AdminRefundRecoveryTest {

    private static final OffsetDateTime START = OffsetDateTime.now().plusDays(10).withNano(0);

    @MockitoBean
    private PaymentProvider paymentProvider;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private BookingService bookingService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PaymentReconcileService reconcileService;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private BookingRepository bookingRepository;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private String adminToken;
    private User owner;
    private Booking booking;
    private Payment paid;

    @BeforeEach
    void setUp() {
        adminToken = data.bearer(data.admin("Refund Admin"));
        owner = data.owner("Refund Owner");
        Stadium stadium = data.stadium(owner, "Arena R17");
        booking = data.booking(stadium, data.user("Refund Player"), START, "confirmed");
        paid = data.cardPayment(booking, 20_310_000L, 20_304_569L, 310_000L, 304_650L, Payment.Status.PAID);
        paid.setProviderSuccessTxnIds("succ-1,succ-2");
        paymentRepository.save(paid);
        reset(paymentProvider);
    }

    private void declineReverses() {
        doThrow(new PaymentProviderException("STPIMS-ERR-057", "Transaction declined by issuer"))
                .when(paymentProvider).reverse(anyString(), anyString());
    }

    private Payment failedRefund() {
        return paymentRepository.findByBookingId(booking.getId()).stream()
                .filter(p -> p.getPaymentType() == Payment.PaymentType.REFUND)
                .findFirst().orElseThrow();
    }

    private Payment reload(Payment payment) {
        return paymentRepository.findById(payment.getId()).orElseThrow();
    }

    /**
     * The work so far would commit: nothing on the way marked the transaction rollback-only,
     * which is what a provider error escaping a @Transactional service method does.
     */
    private void assertNotMarkedForRollback() {
        TransactionStatus current = transactionManager.getTransaction(
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_MANDATORY));
        assertThat(current.isRollbackOnly()).isFalse();
        transactionManager.commit(current); // joins the test transaction: commits nothing yet
    }

    // ---- A declined refund is recorded, the cancellation stands ----

    @Test
    void aDeclinedRefundDoesNotUndoTheCancellation() throws Exception {
        declineReverses();

        bookingService.cancelByOwner(owner.getId(), booking.getId(), "Pitch flooded");

        assertNotMarkedForRollback();
        assertThat(bookingRepository.findById(booking.getId()).orElseThrow().getStatus())
                .isEqualTo("cancelled_by_owner");
        assertThat(reload(paid).getStatus()).isEqualTo(Payment.Status.REFUND_FAILED);
        Payment refund = failedRefund();
        assertThat(refund.getStatus()).isEqualTo(Payment.Status.FAILED);
        assertThat(refund.getAmount()).isEqualTo(-20_310_000L);
        assertThat(refund.getFailureReason()).isEqualTo("Transaction declined by issuer (STPIMS-ERR-057)");

        mvc.perform(get("/api/admin/audit-log").header("Authorization", adminToken)
                        .param("action", "REFUND_FAILED").param("entityId", paid.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].actor.type").value("SYSTEM"))
                .andExpect(jsonPath("$.data.content[0].reason").value("Transaction declined by issuer (STPIMS-ERR-057)"));
        mvc.perform(get("/api/admin/stats").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.failedRefunds").value(1))
                .andExpect(jsonPath("$.data.oldestFailedRefundAt").exists());
        mvc.perform(get("/api/admin/payments/" + refund.getId()).header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.failureReason").value("Transaction declined by issuer (STPIMS-ERR-057)"))
                .andExpect(jsonPath("$.data.refundedManually").doesNotExist());
    }

    @Test
    void withNoFailedRefundTheStatsSayNothingOfThem() throws Exception {
        mvc.perform(get("/api/admin/stats").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.failedRefunds").value(0))
                .andExpect(jsonPath("$.data.oldestFailedRefundAt").doesNotExist());
    }

    // ---- Retry ----

    @Test
    void aRetryThatIsDeclinedAgainKeepsItFailedWithTheNewReason() throws Exception {
        declineReverses();
        paymentService.refundInFull(paid, "Cancelled");
        Payment refund = failedRefund();
        doThrow(new PaymentProviderException("STPIMS-ERR-061", "Card blocked"))
                .when(paymentProvider).reverse(anyString(), anyString());

        mvc.perform(post("/api/admin/payments/" + refund.getId() + "/retry-refund")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Refund retried"))
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.data.failureReason").value("Card blocked (STPIMS-ERR-061)"));
        assertThat(reload(paid).getStatus()).isEqualTo(Payment.Status.REFUND_FAILED);
    }

    @Test
    void aRetryThatGoesThroughRefundsTheSameRowAndCannotRunTwice() throws Exception {
        declineReverses();
        paymentService.refundInFull(paid, "Cancelled");
        Payment refund = failedRefund();
        reset(paymentProvider);
        doNothing().when(paymentProvider).reverse(anyString(), anyString());

        mvc.perform(post("/api/admin/payments/" + refund.getId() + "/retry-refund")
                        .header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("refunded"))
                .andExpect(jsonPath("$.data.failureReason").doesNotExist())
                .andExpect(jsonPath("$.data.id").value(refund.getId().toString()));

        // The same transactions, both splits — and no second refund row.
        verify(paymentProvider).reverse("succ-1", "Cancelled");
        verify(paymentProvider).reverse("succ-2", "Cancelled");
        assertThat(reload(paid).getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(paymentRepository.findByRefundOfPaymentIdOrderByCreatedAtAsc(paid.getId())).hasSize(1);
        mvc.perform(get("/api/admin/stats").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.failedRefunds").value(0));
        mvc.perform(get("/api/admin/audit-log").header("Authorization", adminToken)
                        .param("action", "REFUND_RETRIED").param("entityId", refund.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].actor.type").value("ADMIN"))
                .andExpect(jsonPath("$.data.content[0].changes[0].field").value("status"))
                .andExpect(jsonPath("$.data.content[0].changes[0].oldValue").value("failed"))
                .andExpect(jsonPath("$.data.content[0].changes[0].newValue").value("refunded"));

        mvc.perform(post("/api/admin/payments/" + refund.getId() + "/retry-refund")
                        .header("Authorization", adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a failed refund can be retried"));
    }

    @Test
    void anUnreachableProviderIs502AndChangesNothing() throws Exception {
        declineReverses();
        paymentService.refundInFull(paid, "Cancelled");
        Payment refund = failedRefund();
        doThrow(new ProviderTimeoutException("reverse", null)).when(paymentProvider).reverse(anyString(), anyString());

        mvc.perform(post("/api/admin/payments/" + refund.getId() + "/retry-refund")
                        .header("Authorization", adminToken))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("Payment provider unavailable"));
        assertThat(reload(refund).getStatus()).isEqualTo(Payment.Status.FAILED);
    }

    @Test
    void onlyAFailedRefundCanBeRetried() throws Exception {
        mvc.perform(post("/api/admin/payments/" + paid.getId() + "/retry-refund")
                        .header("Authorization", adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a failed refund can be retried"));
        mvc.perform(post("/api/admin/payments/" + UUID.randomUUID() + "/retry-refund")
                        .header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment not found"));
    }

    // ---- Marked refunded by hand ----

    @Test
    void aFailedRefundCanBeMarkedRefundedByHand() throws Exception {
        declineReverses();
        paymentService.refundInFull(paid, "Cancelled");
        Payment refund = failedRefund();
        reset(paymentProvider);

        mvc.perform(post("/api/admin/payments/" + refund.getId() + "/mark-refunded")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reason must be at least 10 characters"));
        mvc.perform(post("/api/admin/payments/" + refund.getId() + "/mark-refunded")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reason is required"));

        mvc.perform(post("/api/admin/payments/" + refund.getId() + "/mark-refunded")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Returned in cash at the stadium on 30.10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Refund marked as refunded"))
                .andExpect(jsonPath("$.data.status").value("refunded"))
                .andExpect(jsonPath("$.data.refundedManually").value(true))
                .andExpect(jsonPath("$.data.manualRefundReason").value("Returned in cash at the stadium on 30.10"));

        // Recorded only: the money went back outside the platform, so the provider is not called.
        verify(paymentProvider, never()).reverse(anyString(), anyString());
        assertThat(reload(paid).getStatus()).isEqualTo(Payment.Status.REFUNDED);
        mvc.perform(get("/api/admin/audit-log").header("Authorization", adminToken)
                        .param("action", "REFUND_MARKED_MANUAL").param("entityId", refund.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].reason").value("Returned in cash at the stadium on 30.10"));
        mvc.perform(post("/api/admin/payments/" + refund.getId() + "/mark-refunded")
                        .header("Authorization", adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Returned in cash at the stadium on 30.10\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a failed refund can be marked as refunded"));
    }

    // ---- Not failed refunds ----

    @Test
    void aHoldThatCannotBeReleasedIsLeftForTheJobNotCountedAsAFailedRefund() throws Exception {
        Payment held = data.cardPayment(booking, 7_620_000L, 5_076_143L, 120_000L, null, Payment.Status.HELD);
        doThrow(new PaymentProviderException("STPIMS-ERR-500", "Internal error"))
                .when(paymentProvider).releaseHold(anyString());

        paymentService.giveBackInFull(held, "Slot taken");

        assertNotMarkedForRollback();
        assertThat(reload(held).getStatus()).isEqualTo(Payment.Status.RETURN_PENDING);
        assertThat(paymentRepository.findByRefundOfPaymentIdOrderByCreatedAtAsc(held.getId())).isEmpty();
        mvc.perform(get("/api/admin/stats").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.failedRefunds").value(0));
    }

    @Test
    void theReconcileJobRecordsADeclinedReverseAndThenLeavesItToAnAdmin() {
        paid.setStatus(Payment.Status.RETURN_PENDING);
        paymentRepository.save(paid);
        declineReverses();

        reconcileService.reconcile(paid.getId());

        assertThat(reload(paid).getStatus()).isEqualTo(Payment.Status.REFUND_FAILED);
        assertThat(failedRefund().getStatus()).isEqualTo(Payment.Status.FAILED);

        // The job never looks at it again: neither status is one it settles.
        reset(paymentProvider);
        reconcileService.reconcile(paid.getId());
        reconcileService.reconcile(failedRefund().getId());
        verify(paymentProvider, never()).reverse(anyString(), anyString());
        assertThat(paymentRepository.findByRefundOfPaymentIdOrderByCreatedAtAsc(paid.getId())).hasSize(1);
    }

    @Test
    void aHoldAlreadyGoneIsStillReleasedNormally() {
        Payment held = data.cardPayment(booking, 7_620_000L, 5_076_143L, 120_000L, null, Payment.Status.HELD);
        doThrow(new HoldCancelledException("STPIMS-ERR-145")).when(paymentProvider).releaseHold(anyString());

        paymentService.giveBackInFull(held, "Slot taken");

        assertThat(reload(held).getStatus()).isEqualTo(Payment.Status.RELEASED);
    }

    // ---- Access ----

    @Test
    void onlyAnAdminMayRetryOrMarkRefunds() throws Exception {
        String player = data.bearer(data.user("Sneaky"));
        List.of("/retry-refund", "/mark-refunded").forEach(path -> {
            try {
                mvc.perform(post("/api/admin/payments/" + paid.getId() + path))
                        .andExpect(status().isUnauthorized());
                mvc.perform(post("/api/admin/payments/" + paid.getId() + path).header("Authorization", player))
                        .andExpect(status().isForbidden());
            } catch (Exception ex) {
                throw new AssertionError(ex);
            }
        });
    }
}
