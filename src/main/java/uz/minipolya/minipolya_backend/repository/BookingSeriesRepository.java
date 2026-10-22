package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.BookingSeries;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingSeriesRepository extends JpaRepository<BookingSeries, UUID> {

    List<BookingSeries> findByUserIdAndIsActiveTrue(UUID userId);

    List<BookingSeries> findByStadiumIdAndIsActiveTrue(UUID stadiumId);

    /** All active series — used by BookingSeriesJob. */
    List<BookingSeries> findByIsActiveTrue();
}
