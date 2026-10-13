package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.GameParticipant;

import java.util.List;
import java.util.Optional;

@Repository
public interface GameParticipantRepository extends JpaRepository<GameParticipant, Long> {

    List<GameParticipant> findByGameId(Long gameId);

    Optional<GameParticipant> findByGameIdAndUserId(Long gameId, Long userId);

    List<GameParticipant> findByUserId(Long userId);

    int countByGameIdAndStatus(Long gameId, GameParticipant.Status status);

    List<GameParticipant> findByGameIdAndSide(Long gameId, GameParticipant.Side side);
}
