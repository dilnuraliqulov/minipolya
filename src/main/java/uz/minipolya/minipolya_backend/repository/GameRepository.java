package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameTeam;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface GameRepository extends JpaRepository<Game, UUID>, JpaSpecificationExecutor<Game> {

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

    /**
     * Waiting team games an opponent could still join: before the 3-hour point,
     * and not one the viewer's own team is waiting on.
     *
     * <p>{@code excludedTeamIds} is never empty — an empty {@code IN (...)} is
     * not valid SQL — so the caller pads it with a sentinel id.
     *
     * <p><b>No parameter here is ever null.</b> A bare {@code :param IS NULL}
     * renders as an untyped {@code ? is null}, and PostgreSQL has nothing to
     * infer a type from in that position: a null timestamp or uuid bind arrives
     * as {@code Oid.UNSPECIFIED} and the statement is rejected outright with
     * {@code 42P18 could not determine data type of parameter}. So each
     * optional filter is switched off by a boolean flag instead, and
     * {@code from}/{@code to} carry a wide-open range rather than nulls. A
     * non-null bind compared against a column is fine — the column gives the
     * type — which is why the remaining comparisons need no casts.
     */
    @Query("""
            SELECT g FROM Game g
            WHERE g.type = 'team_vs_team'
              AND g.status = 'open'
              AND g.startsAt >= :from
              AND g.startsAt < :to
              AND (:anyStadium = TRUE OR g.stadium.id = :stadiumId)
              AND (:anyFormat = TRUE OR g.stadium.format = :format)
              AND NOT EXISTS (
                  SELECT 1 FROM GameTeam t
                  WHERE t.game = g
                    AND t.status = :activeStatus
                    AND t.team.id IN :excludedTeamIds)
            ORDER BY g.startsAt ASC
            """)
    Page<Game> findChallenges(@Param("from") OffsetDateTime from,
                              @Param("to") OffsetDateTime to,
                              @Param("anyStadium") boolean anyStadium,
                              @Param("stadiumId") UUID stadiumId,
                              @Param("anyFormat") boolean anyFormat,
                              @Param("format") String format,
                              Pageable pageable,
                              @Param("excludedTeamIds") List<UUID> excludedTeamIds,
                              @Param("activeStatus") GameTeam.Status activeStatus);

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
