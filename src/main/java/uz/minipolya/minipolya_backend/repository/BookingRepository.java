package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Booking;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByUserIdAndStatusIn(UUID userId, Collection<String> statuses);

    List<Booking> findByStadiumIdAndStartsAtBetween(UUID stadiumId, OffsetDateTime from, OffsetDateTime to);

    Page<Booking> findByStadiumIdAndStartsAtBetween(UUID stadiumId, OffsetDateTime from, OffsetDateTime to,
                                                    Pageable pageable);

    /** Used by BookingExpiryJob to find pending bookings past their hold deadline. */
    List<Booking> findByStatusAndExpiresAtBefore(String status, OffsetDateTime time);

    /** Used by ReminderJob to find bookings starting within a window. */
    List<Booking> findByStatusAndStartsAtBetween(String status, OffsetDateTime from, OffsetDateTime to);

    Page<Booking> findByUserId(UUID userId, Pageable pageable);

    Page<Booking> findByStadiumId(UUID stadiumId, Pageable pageable);

    /** Bookings generated from a recurring series. */
    List<Booking> findByBookingSeriesId(UUID bookingSeriesId);

    long countByStadiumIdAndStatusIn(UUID stadiumId, Collection<String> statuses);

    long countByUserId(UUID userId);

    long countByStatusIn(Collection<String> statuses);

    long countByStatusInAndStartsAtBetween(Collection<String> statuses, OffsetDateTime from, OffsetDateTime to);

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
}
