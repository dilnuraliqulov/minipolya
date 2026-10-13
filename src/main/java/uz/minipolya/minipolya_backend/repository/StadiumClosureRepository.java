package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.StadiumClosure;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface StadiumClosureRepository extends JpaRepository<StadiumClosure, Long> {

    List<StadiumClosure> findByStadiumId(Long stadiumId);

    /** Closures that are active or still upcoming on/after the given date. */
    List<StadiumClosure> findByStadiumIdAndEndDateGreaterThanEqual(Long stadiumId, LocalDate date);
}
