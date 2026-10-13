package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Payment;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findByBookingId(Long bookingId);

    List<Payment> findByGameId(Long gameId);

    Page<Payment> findByUserId(Long userId, Pageable pageable);

    /** Looks up a payment by its provider transaction id — the webhook idempotency key. */
    Optional<Payment> findByProviderAndProviderTxnId(Payment.Provider provider, String providerTxnId);

    Optional<Payment> findByBookingIdAndPaymentTypeAndStatus(Long bookingId,
                                                             Payment.PaymentType paymentType,
                                                             Payment.Status status);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM Payment p JOIN p.booking b
            WHERE b.stadium.id = :stadiumId
              AND p.status = :status AND p.paymentType = :type
              AND p.createdAt >= :from AND p.createdAt < :to
            """)
    long sumRevenueByStadiumAndDateRange(@Param("stadiumId") Long stadiumId,
                                         @Param("status") Payment.Status status,
                                         @Param("type") Payment.PaymentType type,
                                         @Param("from") OffsetDateTime from,
                                         @Param("to") OffsetDateTime to);

    @Query("""
            SELECT COALESCE(SUM(p.amount), 0) FROM Payment p JOIN p.booking b
            WHERE b.stadium.id = :stadiumId
              AND p.status = :status AND p.paymentType = :type
            """)
    long sumRevenueByStadium(@Param("stadiumId") Long stadiumId,
                             @Param("status") Payment.Status status,
                             @Param("type") Payment.PaymentType type);

    @Query("""
            SELECT p FROM Payment p JOIN p.booking b
            WHERE b.stadium.id = :stadiumId
              AND p.status = :status AND p.paymentType = :type
              AND p.createdAt >= :from AND p.createdAt < :to
            """)
    List<Payment> findStadiumBookingPayments(@Param("stadiumId") Long stadiumId,
                                             @Param("status") Payment.Status status,
                                             @Param("type") Payment.PaymentType type,
                                             @Param("from") OffsetDateTime from,
                                             @Param("to") OffsetDateTime to);

    // ---- Platform-wide (admin) revenue; amount > 0 excludes refund rows ----

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p WHERE p.status = :status AND p.amount > 0")
    long sumTotalRevenue(@Param("status") Payment.Status status);

    @Query("SELECT COALESCE(SUM(p.amount), 0) FROM Payment p "
            + "WHERE p.status = :status AND p.amount > 0 AND p.createdAt >= :from")
    long sumRevenueAfter(@Param("status") Payment.Status status, @Param("from") OffsetDateTime from);

    @Query("SELECT p FROM Payment p WHERE p.status = :status AND p.amount > 0 AND p.createdAt >= :from")
    List<Payment> findPositiveCompletedSince(@Param("status") Payment.Status status,
                                             @Param("from") OffsetDateTime from);
}
