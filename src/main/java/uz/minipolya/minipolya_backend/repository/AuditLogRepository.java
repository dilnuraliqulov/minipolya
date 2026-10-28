package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.repository.Repository;
import uz.minipolya.minipolya_backend.entity.AuditLog;

import java.util.Optional;
import java.util.UUID;

/**
 * Append and read only. This deliberately extends the bare {@link Repository} rather
 * than JpaRepository, so no update or delete method exists for any caller — together
 * with {@code @Immutable} on the entity, an audit entry cannot be changed or removed
 * through the application.
 */
@org.springframework.stereotype.Repository
public interface AuditLogRepository extends Repository<AuditLog, UUID>, AuditLogRepositoryCustom {

    AuditLog save(AuditLog auditLog);

    Optional<AuditLog> findById(UUID id);
}
