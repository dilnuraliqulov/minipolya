package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Favorite;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {

    Page<Favorite> findByUserId(UUID userId, Pageable pageable);

    Optional<Favorite> findByUserIdAndStadiumId(UUID userId, UUID stadiumId);

    boolean existsByUserIdAndStadiumId(UUID userId, UUID stadiumId);

    void deleteByUserIdAndStadiumId(UUID userId, UUID stadiumId);
}
