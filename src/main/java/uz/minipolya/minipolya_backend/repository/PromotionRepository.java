package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Promotion;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, UUID> {

    List<Promotion> findByStadiumIdAndIsActiveTrue(UUID stadiumId);

    Page<Promotion> findByStadiumId(UUID stadiumId, Pageable pageable);

    /** Used by PromotionExpiryJob to find active promotions whose window has ended. */
    List<Promotion> findByIsActiveTrueAndEndsAtBefore(OffsetDateTime now);

    /** Active promotions for a stadium that cover the given instant, weekday and time. */
    @Query("""
            SELECT p FROM Promotion p
            WHERE p.stadium.id = :stadiumId
              AND p.isActive = true
              AND p.startsAt <= :dateTime
              AND p.endsAt >= :dateTime
              AND (p.dayOfWeek IS NULL OR p.dayOfWeek = :dayOfWeek)
              AND (p.startTime IS NULL OR (p.startTime <= :time AND p.endTime > :time))
            """)
    List<Promotion> findMatching(@Param("stadiumId") UUID stadiumId,
                                 @Param("dateTime") OffsetDateTime dateTime,
                                 @Param("dayOfWeek") int dayOfWeek,
                                 @Param("time") LocalTime time);
}
