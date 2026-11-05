package uz.minipolya.minipolya_backend.provider.fake;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.service.BookingService;
import uz.minipolya.minipolya_backend.service.PaymentService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The business code never asks the provider to reverse the same payment twice — real ATMOS
 * would answer a second reverse with an error, and we would record a second refund. Two
 * cancellations at the same moment, or one acting on a view of the payment that another
 * has already refunded, reverse it once.
 *
 * <p>Concurrency needs real commits, so these tests run outside the usual rolled-back test
 * transaction and remove what they created afterwards.
 */
@DatabaseTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NoDoubleReverseTest extends FakeProviderFlowSupport {

    @Autowired
    private BookingService bookingService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<User> created = new ArrayList<>();

    private User user(String name) {
        User user = data.user(name);
        created.add(user);
        return user;
    }

    private Payment paidBooking(User player, int hour) throws Exception {
        UUID cardId = addCard(player, "8600123412341234");
        UUID bookingId = bookingId(book(player, cardId, "FULL", slot(4, hour)).andExpect(status().isOk()));
        Payment paid = onlyPayment(bookingId, Payment.PaymentType.BOOKING);
        assertThat(paid.getStatus()).isEqualTo(Payment.Status.PAID);
        return paid;
    }

    /** Every reverse the provider was asked for, for this payment's transactions. */
    private int reversesAsked(Payment payment) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM fake_payment_reverses r
                WHERE r.transaction_id = ANY (string_to_array(?, ','))
                """, Integer.class, payment.getProviderSuccessTxnIds());
    }

    private int refundRows(Payment payment) {
        return payments.findByRefundOfPaymentIdOrderByCreatedAtAsc(payment.getId()).size();
    }

    @Test
    void aRefundActingOnAStaleViewOfAnAlreadyRefundedPaymentDoesNotReverseAgain() throws Exception {
        User player = user("Stale Player");
        Payment paid = paidBooking(player, 10);

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            // This transaction has the payment in hand, still "paid"...
            Payment stale = payments.findById(paid.getId()).orElseThrow();
            assertThat(stale.getStatus()).isEqualTo(Payment.Status.PAID);
            // ...when another one cancels the booking and refunds it, and commits.
            CompletableFuture.runAsync(() -> bookingService.cancelByUser(player.getId(),
                    paid.getBooking().getId(), "first")).join();
            // Now this one gives the same payment back.
            assertThat(paymentService.refundInFull(stale, "second")).isNull();
        });

        assertThat(reversesAsked(paid)).isEqualTo(1);
        assertThat(refundRows(paid)).isEqualTo(1);
        assertThat(payments.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(balance(savedCardOf(player))).isEqualTo(START);
    }

    @Test
    void theOwnerAndThePlayerCancellingAtTheSameMomentReverseOnce() throws Exception {
        User player = user("Race Player");
        Payment paid = paidBooking(player, 11);
        UUID bookingId = paid.getBooking().getId();
        CyclicBarrier together = new CyclicBarrier(2);

        CompletableFuture<Void> byPlayer = CompletableFuture.runAsync(() -> {
            await(together);
            ignoreRefusal(() -> bookingService.cancelByUser(player.getId(), bookingId, "player"));
        });
        CompletableFuture<Void> byOwner = CompletableFuture.runAsync(() -> {
            await(together);
            ignoreRefusal(() -> bookingService.cancelByOwner(owner.getId(), bookingId, "owner"));
        });
        CompletableFuture.allOf(byPlayer, byOwner).get(30, TimeUnit.SECONDS);

        assertThat(reversesAsked(paid)).isEqualTo(1);
        assertThat(refundRows(paid)).isEqualTo(1);
        assertThat(payments.findById(paid.getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void twoGiveBacksOfTheSameTeamPaymentAtOnceReverseOnce() throws Exception {
        User player = user("Give Back Player");
        Payment paid = paidBooking(player, 12);
        CyclicBarrier together = new CyclicBarrier(2);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        Runnable giveBack = () -> {
            await(together);
            tx.executeWithoutResult(s -> paymentService.giveBackInFull(
                    payments.findById(paid.getId()).orElseThrow(), "twice"));
        };
        CompletableFuture.allOf(CompletableFuture.runAsync(giveBack), CompletableFuture.runAsync(giveBack))
                .get(30, TimeUnit.SECONDS);

        assertThat(reversesAsked(paid)).isEqualTo(1);
        assertThat(refundRows(paid)).isEqualTo(1);
    }

    private UUID savedCardOf(User user) {
        return savedCards.findAll().stream()
                .filter(c -> c.getUser().getId().equals(user.getId()))
                .map(SavedCard::getId).findFirst().orElseThrow();
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The loser of a race may be told the booking is no longer cancellable; that is fine. */
    private static void ignoreRefusal(Runnable action) {
        try {
            action.run();
        } catch (uz.minipolya.minipolya_backend.exception.BadRequestException ex) {
            // already cancelled by the other one
        }
    }

    /** These tests commit: remove everything they made. */
    @AfterEach
    void removeWhatWasCommitted() {
        List<UUID> users = new ArrayList<>(created.stream().map(User::getId).toList());
        users.add(owner.getId());
        UUID stadiumId = stadium.getId();
        List<UUID> paymentIds = jdbc.queryForList("""
                SELECT id FROM payments WHERE booking_id IN (SELECT id FROM bookings WHERE stadium_id = ?)
                   OR refund_of_payment_id IN (SELECT id FROM payments
                                               WHERE booking_id IN (SELECT id FROM bookings WHERE stadium_id = ?))
                """, UUID.class, stadiumId, stadiumId);
        for (UUID paymentId : paymentIds) {
            jdbc.update("DELETE FROM audit_log WHERE entity_id = ?", paymentId);
        }
        jdbc.update("""
                DELETE FROM payments WHERE refund_of_payment_id IN (SELECT id FROM payments
                    WHERE booking_id IN (SELECT id FROM bookings WHERE stadium_id = ?))
                """, stadiumId);
        jdbc.update("DELETE FROM payments WHERE booking_id IN (SELECT id FROM bookings WHERE stadium_id = ?)", stadiumId);
        jdbc.update("DELETE FROM audit_log WHERE entity_id IN (SELECT id FROM bookings WHERE stadium_id = ?)", stadiumId);
        jdbc.update("DELETE FROM audit_log WHERE entity_id = ?", stadiumId);
        jdbc.update("DELETE FROM stadiums WHERE id = ?", stadiumId);
        for (UUID userId : users) {
            jdbc.update("DELETE FROM audit_log WHERE user_id = ? OR entity_id = ?", userId, userId);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM saved_cards WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM owner_legal_details WHERE user_id = ?", userId);
            jdbc.update("DELETE FROM users WHERE id = ?", userId);
        }
    }
}
