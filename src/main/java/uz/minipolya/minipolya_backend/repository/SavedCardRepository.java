package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.SavedCard;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SavedCardRepository extends JpaRepository<SavedCard, UUID> {

    /** A player's usable cards; a deleted card is inactive, not removed. */
    List<SavedCard> findByUserIdAndIsActiveTrue(UUID userId);

    Optional<SavedCard> findByIdAndUserIdAndIsActiveTrue(UUID id, UUID userId);
}
