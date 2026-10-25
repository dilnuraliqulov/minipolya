package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Game;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface GameRepository extends JpaRepository<Game, UUID> {

    List<Game> findByStadiumIdAndStatus(UUID stadiumId, String status);

    long countByStatus(String status);

    List<Game> findByCreatorId(UUID creatorId);

    /** Used by GameTimeoutJob to find games past their start time. */
    List<Game> findByStatusAndStartsAtBefore(String status, OffsetDateTime time);

    /**
     * Team games that have run out of time to find an opponent. Their cutoff is
     * the 3-hour point, not kick-off: a team arriving later could not get there.
     */
    List<Game> findByStatusAndTypeAndStartsAtBefore(String status, String type, OffsetDateTime time);

    /** Every other game type, which still times out at its start time. */
    List<Game> findByStatusAndTypeNotAndStartsAtBefore(String status, String type, OffsetDateTime time);

    /** Public games discoverable in the app (the "open — find players" listing). */
    List<Game> findByIsPublicTrueAndStatus(String status);

    Page<Game> findByIsPublicTrueAndStatus(String status, Pageable pageable);

    Page<Game> findByStadiumIdAndIsPublicTrueAndStatus(UUID stadiumId, String status, Pageable pageable);

    /** True if either side of any game still references the team (blocks team deletion). */
    boolean existsByTeamAIdOrTeamBId(UUID teamAId, UUID teamBId);

    /** Games the user created or joined (for the "my games" listing). */
    @Query("""
            SELECT g FROM Game g
            WHERE g.creator.id = :userId
               OR g.id IN (SELECT p.game.id FROM GameParticipant p WHERE p.user.id = :userId)
            """)
    Page<Game> findMyGames(@Param("userId") UUID userId, Pageable pageable);
}
