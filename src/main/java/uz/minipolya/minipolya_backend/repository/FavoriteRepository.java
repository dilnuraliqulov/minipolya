package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Favorite;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FavoriteRepository extends JpaRepository<Favorite, UUID> {

    /** A page of a user's favorites, each with its stadium, owner and amenities in the same query. */
    @EntityGraph(attributePaths = {"stadium", "stadium.owner", "stadium.amenities"})
    Page<Favorite> findByUserId(UUID userId, Pageable pageable);

    /** Which of these stadiums the user has favorited: one query for a whole list page. */
    @Query("SELECT f.stadium.id FROM Favorite f WHERE f.user.id = :userId AND f.stadium.id IN :ids")
    List<UUID> findFavoritedStadiumIds(@Param("userId") UUID userId, @Param("ids") Collection<UUID> ids);

    Optional<Favorite> findByUserIdAndStadiumId(UUID userId, UUID stadiumId);

    boolean existsByUserIdAndStadiumId(UUID userId, UUID stadiumId);

    void deleteByUserIdAndStadiumId(UUID userId, UUID stadiumId);
}
