package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StadiumRepository extends JpaRepository<Stadium, UUID>, JpaSpecificationExecutor<Stadium> {

    Optional<Stadium> findByIdAndStatus(UUID id, String status);

    List<Stadium> findByOwnerId(UUID ownerId);

    long countByStatus(String status);

    long countByOwnerId(UUID ownerId);

    /** Every photo of several stadiums in one query, each stadium's in order. */
    @Query("SELECT p FROM StadiumPhoto p WHERE p.stadium.id IN :ids ORDER BY p.position ASC")
    List<StadiumPhoto> findPhotosByStadiumIds(@Param("ids") Collection<UUID> ids);

    /**
     * Public search over approved, active stadiums. Every filter is optional —
     * a null argument disables that clause.
     */
    @Query("""
        SELECT s FROM Stadium s
        WHERE s.status = 'approved'
          AND s.isActive = true
          AND (CAST(:q AS string) IS NULL
               OR LOWER(s.name) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%'))
               OR LOWER(s.address) LIKE LOWER(CONCAT('%', CAST(:q AS string), '%')))
          AND (CAST(:format AS string) IS NULL OR s.format = CAST(:format AS string))
          AND (:maxPrice IS NULL OR s.pricePerHour <= :maxPrice)
        """)
    Page<Stadium> search(@Param("q") String q,
                         @Param("format") String format,
                         @Param("maxPrice") Long maxPrice,
                         Pageable pageable);
}
