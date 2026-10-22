package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import uz.minipolya.minipolya_backend.entity.OwnerApplication;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface OwnerApplicationRepository extends JpaRepository<OwnerApplication, UUID> {

    List<OwnerApplication> findByUserId(UUID userId);

    /** Used to block a second application while one is still open. */
    List<OwnerApplication> findByUserIdAndStatusIn(UUID userId, Collection<OwnerApplication.Status> statuses);

    Page<OwnerApplication> findByStatus(OwnerApplication.Status status, Pageable pageable);

    long countByStatus(OwnerApplication.Status status);
}
