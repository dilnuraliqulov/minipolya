package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Review;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReviewRepository extends JpaRepository<Review, UUID> {

    Page<Review> findByStadiumIdOrderByCreatedAtDesc(UUID stadiumId, Pageable pageable);

    Page<Review> findByUserId(UUID userId, Pageable pageable);

    Optional<Review> findByStadiumIdAndUserId(UUID stadiumId, UUID userId);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.stadium.id = :stadiumId")
    Double averageRatingByStadiumId(@Param("stadiumId") UUID stadiumId);

    int countByStadiumId(UUID stadiumId);

    /** {@code [stadiumId, average rating, review count]} for several stadiums at once — for a list. */
    @Query("SELECT r.stadium.id, AVG(r.rating), COUNT(r) FROM Review r WHERE r.stadium.id IN :ids GROUP BY r.stadium.id")
    List<Object[]> ratingsByStadiumIds(@Param("ids") Collection<UUID> ids);
}
