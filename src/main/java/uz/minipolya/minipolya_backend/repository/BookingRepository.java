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

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    List<Booking> findByUserIdAndStatusIn(Long userId, Collection<String> statuses);

    List<Booking> findByStadiumIdAndStartsAtBetween(Long stadiumId, OffsetDateTime from, OffsetDateTime to);

    /** Used by BookingExpiryJob to find pending bookings past their hold deadline. */
    List<Booking> findByStatusAndExpiresAtBefore(String status, OffsetDateTime time);

    Page<Booking> findByUserId(Long userId, Pageable pageable);

    Page<Booking> findByStadiumId(Long stadiumId, Pageable pageable);

    /** True if an active (pending/confirmed) booking already overlaps the given slot. */
    @Query("""
            SELECT COUNT(b) > 0 FROM Booking b
            WHERE b.stadium.id = :stadiumId
              AND b.status IN ('pending', 'confirmed')
              AND b.startsAt < :endsAt
              AND :startsAt < b.endsAt
            """)
    boolean existsActiveOverlap(@Param("stadiumId") Long stadiumId,
                                @Param("startsAt") OffsetDateTime startsAt,
                                @Param("endsAt") OffsetDateTime endsAt);
}
