package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.Stadium;

import java.util.List;
import java.util.Optional;

@Repository
public interface StadiumRepository extends JpaRepository<Stadium, Long> {

    Optional<Stadium> findByIdAndStatus(Long id, String status);

    List<Stadium> findByOwnerId(Long ownerId);

    Page<Stadium> findByStatus(String status, Pageable pageable);

    long countByStatus(String status);

    long countByOwnerId(Long ownerId);

    @Query("""
            SELECT s FROM Stadium s
            WHERE LOWER(s.name) LIKE LOWER(CONCAT('%', :search, '%'))
               OR LOWER(s.address) LIKE LOWER(CONCAT('%', :search, '%'))
            """)
    Page<Stadium> searchStadiums(@Param("search") String search, Pageable pageable);

    @Query("""
            SELECT s FROM Stadium s
            WHERE s.status = :status
              AND (LOWER(s.name) LIKE LOWER(CONCAT('%', :search, '%'))
                   OR LOWER(s.address) LIKE LOWER(CONCAT('%', :search, '%')))
            """)
    Page<Stadium> searchStadiumsByStatus(@Param("status") String status,
                                         @Param("search") String search, Pageable pageable);

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
          AND (CAST(:format AS string) IS NULL OR s.format = :format)
          AND (:maxPrice IS NULL OR s.pricePerHour <= :maxPrice)
        """)
    Page<Stadium> search(@Param("q") String q,
                         @Param("format") String format,
                         @Param("maxPrice") Integer maxPrice,
                         Pageable pageable);
}
