package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Review;

import java.util.Optional;

@Repository
public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByStadiumIdOrderByCreatedAtDesc(Long stadiumId, Pageable pageable);

    Page<Review> findByUserId(Long userId, Pageable pageable);

    Optional<Review> findByStadiumIdAndUserId(Long stadiumId, Long userId);

    @Query("SELECT AVG(r.rating) FROM Review r WHERE r.stadium.id = :stadiumId")
    Double averageRatingByStadiumId(@Param("stadiumId") Long stadiumId);

    int countByStadiumId(Long stadiumId);
}
