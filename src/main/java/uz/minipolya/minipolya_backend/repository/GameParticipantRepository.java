package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.GameParticipant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GameParticipantRepository extends JpaRepository<GameParticipant, UUID> {

    List<GameParticipant> findByGameId(UUID gameId);

    Optional<GameParticipant> findByGameIdAndUserId(UUID gameId, UUID userId);

    List<GameParticipant> findByUserId(UUID userId);

    int countByGameIdAndStatus(UUID gameId, GameParticipant.Status status);

    List<GameParticipant> findByGameIdAndSide(UUID gameId, GameParticipant.Side side);
}
