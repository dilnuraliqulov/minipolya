package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
