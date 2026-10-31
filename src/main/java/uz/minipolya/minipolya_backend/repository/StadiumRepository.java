package uz.minipolya.minipolya_backend.repository;

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

    /** {@code [ownerId, count]} of the stadiums each of several users owns. */
    @Query("SELECT s.owner.id, COUNT(s) FROM Stadium s WHERE s.owner.id IN :ids GROUP BY s.owner.id")
    List<Object[]> countByOwnerIds(@Param("ids") Collection<UUID> ids);

    /** Every photo of several stadiums in one query, each stadium's in order. */
    @Query("SELECT p FROM StadiumPhoto p WHERE p.stadium.id IN :ids ORDER BY p.position ASC")
    List<StadiumPhoto> findPhotosByStadiumIds(@Param("ids") Collection<UUID> ids);
}
