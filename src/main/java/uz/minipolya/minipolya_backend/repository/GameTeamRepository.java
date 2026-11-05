package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GameTeamRepository extends JpaRepository<GameTeam, UUID> {

    /** The teams currently in a game — one while it waits, two once it is confirmed. */
    List<GameTeam> findByGameIdAndStatus(UUID gameId, GameTeam.Status status);

    Optional<GameTeam> findByGameIdAndSideAndStatus(UUID gameId, GameParticipant.Side side, GameTeam.Status status);

    /** Includes teams that have left, whose payments still belong to the game. */
    List<GameTeam> findByGameId(UUID gameId);

    Optional<GameTeam> findByGameIdAndLeaderIdAndStatus(UUID gameId, UUID leaderId, GameTeam.Status status);

    List<GameTeam> findByGameIdInAndStatus(List<UUID> gameIds, GameTeam.Status status);

    /** The teams currently in the given games, each team loaded with it — for a list. */
    @Query("SELECT gt FROM GameTeam gt JOIN FETCH gt.team WHERE gt.game.id IN :gameIds AND gt.status = :status")
    List<GameTeam> findWithTeamByGameIdInAndStatus(@Param("gameIds") Collection<UUID> gameIds,
                                                   @Param("status") GameTeam.Status status);

    /** Joins that started before {@code before} and never finished, for the reconcile job. */
    List<GameTeam> findByStatusAndJoinedAtBefore(GameTeam.Status status, java.time.OffsetDateTime before);

    /** The row locked, so two drivers of one join take its steps one at a time. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT gt FROM GameTeam gt WHERE gt.id = :id")
    Optional<GameTeam> findForUpdate(@Param("id") UUID id);
}
