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

@Repository
public interface GameRepository extends JpaRepository<Game, Long> {

    List<Game> findByStadiumIdAndStatus(Long stadiumId, String status);

    long countByStatus(String status);

    List<Game> findByCreatorId(Long creatorId);

    /** Used by GameTimeoutJob to find games past their start time. */
    List<Game> findByStatusAndStartsAtBefore(String status, OffsetDateTime time);

    /** Public games discoverable in the app (the "open — find players" listing). */
    List<Game> findByIsPublicTrueAndStatus(String status);

    Page<Game> findByIsPublicTrueAndStatus(String status, Pageable pageable);

    Page<Game> findByStadiumIdAndIsPublicTrueAndStatus(Long stadiumId, String status, Pageable pageable);

    /** Games the user created or joined (for the "my games" listing). */
    @Query("""
            SELECT g FROM Game g
            WHERE g.creator.id = :userId
               OR g.id IN (SELECT p.game.id FROM GameParticipant p WHERE p.user.id = :userId)
            """)
    Page<Game> findMyGames(@Param("userId") Long userId, Pageable pageable);
}
