package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID>, JpaSpecificationExecutor<Booking> {

    List<Booking> findByUserIdAndStatusIn(UUID userId, Collection<String> statuses);

    /**
     * The row, locked until the transaction ends — so the owner's cash and the player's
     * remainder charge settle one at a time, and the second sees the first's outcome.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Booking b WHERE b.id = :id")
    Optional<Booking> findForUpdate(@Param("id") UUID id);

    List<Booking> findByStadiumIdAndStartsAtBetween(UUID stadiumId, OffsetDateTime from, OffsetDateTime to);

    /** A game's bookings in one status — e.g. the pending one a join in progress holds the slot with. */
    List<Booking> findByGameIdAndStatus(UUID gameId, String status);

    Page<Booking> findByStadiumIdAndStartsAtBetween(UUID stadiumId, OffsetDateTime from, OffsetDateTime to,
                                                    Pageable pageable);

    /**
     * Pending bookings past their hold deadline that BookingExpiryJob may actually expire:
     * the ones with <b>no card payment still on its way</b>.
     *
     * <p>A payment is on its way while it is {@code processing} or {@code capture_pending} —
     * the provider has been asked and has not given a clear answer. ATMOS does exactly this:
     * it answers {@code pay/apply} with "connection problem" for money it has taken, and
     * {@code pay/get} says "paid" moments later. Expiring the booking meanwhile freed the
     * slot for somebody else, so when the payment turned out paid the player's money had to
     * be given back and their slot was gone — for a payment that had always been fine.
     *
     * <p>So the slot stays held until the payment is resolved. Nothing waits forever:
     * PaymentReconcileService fails an unconfirmed charge after its own
     * {@code ABANDONED_AFTER}, and the next run of this job then expires the booking.
     */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.status = :status AND b.expiresAt < :time
              AND NOT EXISTS (
                SELECT 1 FROM Payment p
                WHERE p.booking = b
                  AND p.method <> :cash
                  AND p.amount > 0
                  AND p.status IN :inFlight)
            """)
    List<Booking> findExpirablePending(@Param("status") String status,
                                       @Param("time") OffsetDateTime time,
                                       @Param("cash") Payment.Method cash,
                                       @Param("inFlight") Collection<Payment.Status> inFlight);

    /** Used by ReminderJob to find bookings starting within a window. */
    List<Booking> findByStatusAndStartsAtBetween(String status, OffsetDateTime from, OffsetDateTime to);

    /** Used by BookingCompletionJob to find played bookings awaiting completion. */
    List<Booking> findByStatusAndEndsAtBefore(String status, OffsetDateTime time);

    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    /** R29 upcoming: the player's bookings starting at or after {@code from}. */
    Page<Booking> findByUserIdAndStartsAtGreaterThanEqual(UUID userId, OffsetDateTime from, Pageable pageable);

    /** R29 past: the player's bookings that started before {@code before}. */
    Page<Booking> findByUserIdAndStartsAtBefore(UUID userId, OffsetDateTime before, Pageable pageable);

    Page<Booking> findByStadiumId(UUID stadiumId, Pageable pageable);

    /** Bookings generated from a recurring series. */
    List<Booking> findByBookingSeriesId(UUID bookingSeriesId);

    long countByStadiumIdAndStatusIn(UUID stadiumId, Collection<String> statuses);

    long countByUserId(UUID userId);

    /** {@code [userId, count]} of the bookings each of several users made. */
    @Query("SELECT b.user.id, COUNT(b) FROM Booking b WHERE b.user.id IN :ids GROUP BY b.user.id")
    List<Object[]> countByUserIds(@Param("ids") Collection<UUID> ids);

    long countByStatusIn(Collection<String> statuses);

    /** {@code [stadiumId, count]} of bookings in these statuses, for several stadiums at once. */
    @Query("SELECT b.stadium.id, COUNT(b) FROM Booking b WHERE b.stadium.id IN :ids AND b.status IN :statuses GROUP BY b.stadium.id")
    List<Object[]> countByStadiumIdsAndStatusIn(@Param("ids") Collection<UUID> ids,
                                                @Param("statuses") Collection<String> statuses);

    long countByStatusInAndStartsAtBetween(Collection<String> statuses, OffsetDateTime from, OffsetDateTime to);

    /** Bookings in these statuses starting in {@code [from, to)}: a booking at the boundary is in one period only. */
    long countByStatusInAndStartsAtGreaterThanEqualAndStartsAtLessThan(Collection<String> statuses,
                                                                    OffsetDateTime from, OffsetDateTime to);

    /**
     * {@code [Tashkent date, count]} of bookings in these statuses starting in {@code [from, to)},
     * one row per day that has any.
     */
    @Query(value = """
            SELECT CAST(b.starts_at AT TIME ZONE 'Asia/Tashkent' AS date) AS day, COUNT(*) AS bookings
            FROM bookings b
            WHERE b.status IN (:statuses) AND b.starts_at >= :from AND b.starts_at < :to
            GROUP BY day
            """, nativeQuery = true)
    List<Object[]> countStartingPerTashkentDay(@Param("statuses") Collection<String> statuses,
                                               @Param("from") OffsetDateTime from,
                                               @Param("to") OffsetDateTime to);

    @Query("""
            SELECT COUNT(b) FROM Booking b
            WHERE b.stadium.id = :stadiumId
              AND b.startsAt >= :from AND b.startsAt < :to
              AND b.status IN :statuses
            """)
    long countByStadiumAndDateRange(@Param("stadiumId") UUID stadiumId,
                                    @Param("from") OffsetDateTime from,
                                    @Param("to") OffsetDateTime to,
                                    @Param("statuses") Collection<String> statuses);

    /** True if an active (pending/confirmed) booking already overlaps the given slot. */
    @Query("""
            SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.stadium.id = :stadiumId
              AND b.status IN ('pending', 'confirmed')
              AND b.startsAt < :endsAt
              AND :startsAt < b.endsAt
            """)
    boolean existsActiveOverlap(@Param("stadiumId") UUID stadiumId,
                                @Param("startsAt") OffsetDateTime startsAt,
                                @Param("endsAt") OffsetDateTime endsAt);

    /** Active (pending/confirmed) bookings starting within [from, to) — used by availability. */
    @Query("""
            SELECT b FROM Booking b
            WHERE b.stadium.id = :stadiumId
              AND b.status IN ('pending', 'confirmed')
              AND b.startsAt >= :from
              AND b.startsAt < :to
            """)
    List<Booking> findActiveForStadiumBetween(@Param("stadiumId") UUID stadiumId,
                                              @Param("from") OffsetDateTime from,
                                              @Param("to") OffsetDateTime to);

    /** {@link #findActiveForStadiumBetween} for a whole page of stadiums at once. */
    @Query("""
            SELECT new uz.minipolya.minipolya_backend.repository.BookedSpan(b.stadium.id, b.startsAt, b.endsAt)
            FROM Booking b
            WHERE b.stadium.id IN :stadiumIds
              AND b.status IN ('pending', 'confirmed')
              AND b.startsAt >= :from
              AND b.startsAt < :to
            """)
    List<BookedSpan> findActiveSpansBetween(@Param("stadiumIds") List<UUID> stadiumIds,
                                            @Param("from") OffsetDateTime from,
                                            @Param("to") OffsetDateTime to);
}
