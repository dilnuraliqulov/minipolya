package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;

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
}
