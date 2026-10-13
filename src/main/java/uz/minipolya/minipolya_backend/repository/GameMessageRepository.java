package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.GameMessage;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface GameMessageRepository extends JpaRepository<GameMessage, Long> {

    List<GameMessage> findByGameIdOrderByCreatedAtAsc(Long gameId);

    Page<GameMessage> findByGameIdOrderByCreatedAtDesc(Long gameId, Pageable pageable);

    /** Unread count: messages posted since the user last looked. */
    int countByGameIdAndCreatedAtAfter(Long gameId, OffsetDateTime since);
}
