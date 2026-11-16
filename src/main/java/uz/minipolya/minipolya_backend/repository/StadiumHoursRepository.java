package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import uz.minipolya.minipolya_backend.entity.StadiumHours;

import java.util.List;
import java.util.UUID;

public interface StadiumHoursRepository extends JpaRepository<StadiumHours, UUID> {

    /**
     * Every day row of the given stadiums in one query, for a whole page of the stadium
     * list — never one query per stadium.
     */
    List<StadiumHours> findByStadiumIdIn(List<UUID> stadiumIds);
}
