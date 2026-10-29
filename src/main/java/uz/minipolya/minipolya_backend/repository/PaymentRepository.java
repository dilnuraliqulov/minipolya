package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Payment;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID>, JpaSpecificationExecutor<Payment> {

    List<Payment> findByBookingId(UUID bookingId);

    List<Payment> findByGameId(UUID gameId);

    /** Every payment of several bookings in one query — for a list of bookings. */
    List<Payment> findByBookingIdIn(Collection<UUID> bookingIds);

    /** The refunds that give this payment back. */
    List<Payment> findByRefundOfPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    /** Everything one team has paid on one game — the unit team-game refunds work on. */
    List<Payment> findByGameTeamId(UUID gameTeamId);

    /** Rows stuck mid-step: what PaymentReconcileJob settles. */
    List<Payment> findByStatusInAndUpdatedAtBefore(java.util.Collection<Payment.Status> statuses,
                                                   java.time.OffsetDateTime before);

    Page<Payment> findByUserId(UUID userId, Pageable pageable);

    /** Looks up a payment by its provider transaction id — the webhook idempotency key. */
    Optional<Payment> findByProviderAndProviderTxnId(Payment.Provider provider, String providerTxnId);

    Optional<Payment> findByBookingIdAndPaymentTypeAndStatus(UUID bookingId,
                                                             Payment.PaymentType paymentType,
                                                             Payment.Status status);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM Payment p JOIN p.booking b
            WHERE b.stadium.id = :stadiumId
              AND p.status = :status AND p.paymentType = :type
              AND p.createdAt >= :from AND p.createdAt < :to
            """)
    long sumRevenueByStadiumAndDateRange(@Param("stadiumId") UUID stadiumId,
                                         @Param("status") Payment.Status status,
                                         @Param("type") Payment.PaymentType type,
                                         @Param("from") OffsetDateTime from,
                                         @Param("to") OffsetDateTime to);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM Payment p JOIN p.booking b
            WHERE b.stadium.id = :stadiumId
              AND p.status = :status AND p.paymentType = :type
            """)
    long sumRevenueByStadium(@Param("stadiumId") UUID stadiumId,
                             @Param("status") Payment.Status status,
                             @Param("type") Payment.PaymentType type);

    @Query("""
            SELECT p FROM Payment p JOIN p.booking b
            WHERE b.stadium.id = :stadiumId
              AND p.status = :status AND p.paymentType = :type
              AND p.createdAt >= :from AND p.createdAt < :to
            """)
    List<Payment> findStadiumBookingPayments(@Param("stadiumId") UUID stadiumId,
                                             @Param("status") Payment.Status status,
                                             @Param("type") Payment.PaymentType type,
                                             @Param("from") OffsetDateTime from,
                                             @Param("to") OffsetDateTime to);

    /**
     * The card rows behind the revenue figures ({@code RevenueService}): every card payment
     * that arrived and was taken in {@code [from, to)}, and every card refund that completed
     * in it. A refund completes when its row reaches {@code refunded}, which is its last
     * update. Cash is never included.
     */
    @Query("""
            SELECT new uz.minipolya.minipolya_backend.repository.RevenueRow(
                       p.id, COALESCE(b.stadium.id, g.stadium.id), p.paymentType, p.provider,
                       p.amount, p.ownerAmount, p.platformAmount, p.providerCommission,
                       p.createdAt, p.updatedAt, o.provider, o.amount, o.providerCommission)
            FROM Payment p
            LEFT JOIN p.booking b
            LEFT JOIN p.game g
            LEFT JOIN p.refundOfPayment o
            WHERE p.method = :card
              AND ((p.paymentType <> :refund AND p.amount > 0 AND p.status IN :arrived
                    AND p.createdAt >= :from AND p.createdAt < :to)
                OR (p.paymentType = :refund AND p.status = :refunded
                    AND p.updatedAt >= :from AND p.updatedAt < :to))
            """)
    List<RevenueRow> findRevenueRows(@Param("card") Payment.Method card,
                                     @Param("refund") Payment.PaymentType refund,
                                     @Param("arrived") Collection<Payment.Status> arrived,
                                     @Param("refunded") Payment.Status refunded,
                                     @Param("from") OffsetDateTime from,
                                     @Param("to") OffsetDateTime to);

    /** Tiyin of every payment in this status now — what is on hold on players' cards. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = :status")
    long sumByStatus(@Param("status") Payment.Status status);
}
