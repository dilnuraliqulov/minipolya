package uz.minipolya.minipolya_backend.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
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

    /**
     * R38: money that arrived for one stadium's bookings, by method — one row per method
     * ({@code [method, today, week, month]}, tiyin, as players paid it), counted when the
     * payment was taken. {@code from}/{@code to} span all three periods.
     */
    @Query("""
            SELECT p.method,
                   COALESCE(SUM(CASE WHEN p.createdAt >= :todayFrom AND p.createdAt < :todayTo THEN p.amount ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN p.createdAt >= :weekFrom AND p.createdAt < :weekTo THEN p.amount ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN p.createdAt >= :monthFrom AND p.createdAt < :monthTo THEN p.amount ELSE 0 END), 0)
            FROM Payment p JOIN p.booking b
            WHERE b.stadium.id = :stadiumId AND p.status IN :statuses
              AND p.createdAt >= :from AND p.createdAt < :to
            GROUP BY p.method
            """)
    List<Object[]> sumStadiumPaymentsByMethod(@Param("stadiumId") UUID stadiumId,
                                              @Param("statuses") Collection<Payment.Status> statuses,
                                              @Param("todayFrom") OffsetDateTime todayFrom,
                                              @Param("todayTo") OffsetDateTime todayTo,
                                              @Param("weekFrom") OffsetDateTime weekFrom,
                                              @Param("weekTo") OffsetDateTime weekTo,
                                              @Param("monthFrom") OffsetDateTime monthFrom,
                                              @Param("monthTo") OffsetDateTime monthTo,
                                              @Param("from") OffsetDateTime from,
                                              @Param("to") OffsetDateTime to);

    /** Whether a booking has a payment in one of these statuses — e.g. a charge still being confirmed. */
    boolean existsByBookingIdAndStatusIn(UUID bookingId, Collection<Payment.Status> statuses);

    List<Payment> findByGameId(UUID gameId);

    /** Every payment of several bookings in one query — for a list of bookings. */
    List<Payment> findByBookingIdIn(Collection<UUID> bookingIds);

    /** The row, locked until the transaction ends — so two admins cannot settle one refund twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.id = :id")
    Optional<Payment> findForUpdate(@Param("id") UUID id);

    /**
     * Locks the row until the transaction ends and answers its status as committed — read
     * straight from the database, so a payment this transaction loaded earlier (and another
     * one has refunded since) cannot be acted on from its stale copy. Null when no such row.
     */
    @Query(value = "SELECT status FROM payments WHERE id = :id FOR UPDATE", nativeQuery = true)
    String lockStatus(@Param("id") UUID id);

    /** Failed refunds still waiting for an admin: how many, and since when. */
    long countByPaymentTypeAndStatus(Payment.PaymentType paymentType, Payment.Status status);

    Optional<Payment> findFirstByPaymentTypeAndStatusOrderByCreatedAtAsc(Payment.PaymentType paymentType,
                                                                       Payment.Status status);

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
                       p.createdAt, p.updatedAt, o.provider, o.amount, o.providerCommission,
                       p.refundedManually)
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

    /** As {@link #findRevenueRows}, for all time, only for the bookings and games of these stadiums. */
    @Query("""
            SELECT new uz.minipolya.minipolya_backend.repository.RevenueRow(
                       p.id, COALESCE(b.stadium.id, g.stadium.id), p.paymentType, p.provider,
                       p.amount, p.ownerAmount, p.platformAmount, p.providerCommission,
                       p.createdAt, p.updatedAt, o.provider, o.amount, o.providerCommission,
                       p.refundedManually)
            FROM Payment p
            LEFT JOIN p.booking b
            LEFT JOIN p.game g
            LEFT JOIN p.refundOfPayment o
            WHERE p.method = :card
              AND COALESCE(b.stadium.id, g.stadium.id) IN :stadiumIds
              AND ((p.paymentType <> :refund AND p.amount > 0 AND p.status IN :arrived)
                OR (p.paymentType = :refund AND p.status = :refunded))
            """)
    List<RevenueRow> findRevenueRowsForStadiums(@Param("card") Payment.Method card,
                                                @Param("refund") Payment.PaymentType refund,
                                                @Param("arrived") Collection<Payment.Status> arrived,
                                                @Param("refunded") Payment.Status refunded,
                                                @Param("stadiumIds") Collection<UUID> stadiumIds);

    /** Tiyin of every payment in this status now — what is on hold on players' cards. */
    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = :status")
    long sumByStatus(@Param("status") Payment.Status status);
}
