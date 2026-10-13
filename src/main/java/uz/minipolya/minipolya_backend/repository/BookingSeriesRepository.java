package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.BookingSeries;

import java.util.List;

@Repository
public interface BookingSeriesRepository extends JpaRepository<BookingSeries, Long> {

    List<BookingSeries> findByUserIdAndIsActiveTrue(Long userId);

    List<BookingSeries> findByStadiumIdAndIsActiveTrue(Long stadiumId);

    /** All active series — used by BookingSeriesJob. */
    List<BookingSeries> findByIsActiveTrue();
}
