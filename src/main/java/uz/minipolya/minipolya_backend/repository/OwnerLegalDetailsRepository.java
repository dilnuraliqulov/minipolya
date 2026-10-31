package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;

import java.util.UUID;

/** Keyed by the user's id: one row per user. */
@Repository
public interface OwnerLegalDetailsRepository extends JpaRepository<OwnerLegalDetails, UUID> {
}
