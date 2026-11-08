package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.StadiumClosure;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface StadiumClosureRepository extends JpaRepository<StadiumClosure, UUID> {

    List<StadiumClosure> findByStadiumId(UUID stadiumId);

    /** Closures that are active or still upcoming on/after the given date. */
    List<StadiumClosure> findByStadiumIdAndEndDateGreaterThanEqual(UUID stadiumId, LocalDate date);

    /** Every closure touching {@code date} of the given stadiums: a whole page of them at once. */
    @Query("""
            SELECT c FROM StadiumClosure c
            WHERE c.stadium.id IN :stadiumIds
              AND c.startDate <= :date
              AND c.endDate >= :date
            """)
    List<StadiumClosure> findCoveringDate(@Param("stadiumIds") List<UUID> stadiumIds, @Param("date") LocalDate date);
}
