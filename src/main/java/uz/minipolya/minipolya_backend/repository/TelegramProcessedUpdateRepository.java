package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.entity.TelegramProcessedUpdate;

import java.time.OffsetDateTime;

@Repository
public interface TelegramProcessedUpdateRepository extends JpaRepository<TelegramProcessedUpdate, Long> {

    /**
     * Records the update as handled. 1 when this call recorded it, 0 when it was already
     * there: only the caller that gets 1 handles the update, however many deliveries race.
     */
    @Transactional
    @Modifying
    @Query(value = "INSERT INTO telegram_processed_updates (update_id) VALUES (:updateId) ON CONFLICT DO NOTHING",
            nativeQuery = true)
    int claim(@Param("updateId") long updateId);

    @Transactional
    @Modifying
    @Query("DELETE FROM TelegramProcessedUpdate u WHERE u.receivedAt < :before")
    int deleteReceivedBefore(@Param("before") OffsetDateTime before);
}
