package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.GameMessage;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface GameMessageRepository extends JpaRepository<GameMessage, UUID> {

    List<GameMessage> findByGameIdOrderByCreatedAtAsc(UUID gameId);

    Page<GameMessage> findByGameIdOrderByCreatedAtDesc(UUID gameId, Pageable pageable);

    /**
     * The chat as a team that joined partway through should see it. When an
     * opponent walks out and another takes its place, what the previous one
     * said is not the newcomer's business.
     */
    Page<GameMessage> findByGameIdAndCreatedAtAfterOrderByCreatedAtDesc(UUID gameId, OffsetDateTime since,
                                                                        Pageable pageable);

    /**
     * One page of history older than {@code before} — what "load the earlier messages"
     * asks for. A cursor rather than a deeper page number, because a message arriving
     * meanwhile would shift every page boundary under the reader's thumb.
     */
    Page<GameMessage> findByGameIdAndCreatedAtBeforeOrderByCreatedAtDesc(UUID gameId, OffsetDateTime before,
                                                                         Pageable pageable);

    /** The same, for a team that joined partway through: never behind its own join. */
    Page<GameMessage> findByGameIdAndCreatedAtAfterAndCreatedAtBeforeOrderByCreatedAtDesc(
            UUID gameId, OffsetDateTime since, OffsetDateTime before, Pageable pageable);

    /** Unread count: messages posted since the user last looked. */
    int countByGameIdAndCreatedAtAfter(UUID gameId, OffsetDateTime since);
}
