package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Game;

import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {

    List<Game> findByStadiumIdAndStatus(Long stadiumId, String status);

    List<Game> findByCreatorId(Long creatorId);

    /** Used by GameTimeoutJob (step 10) to find games past their start time. */
    List<Game> findByStatusAndStartsAtBefore(String status, OffsetDateTime time);

    /** Public games discoverable in the app (the "open — find players" listing). */
    List<Game> findByIsPublicTrueAndStatus(String status);
}
