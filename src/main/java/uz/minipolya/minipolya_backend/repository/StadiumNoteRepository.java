package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.StadiumNote;

import java.util.List;
import java.util.UUID;

@Repository
public interface StadiumNoteRepository extends JpaRepository<StadiumNote, UUID> {

    List<StadiumNote> findByStadiumIdOrderByCreatedAtDescIdDesc(UUID stadiumId);
}
