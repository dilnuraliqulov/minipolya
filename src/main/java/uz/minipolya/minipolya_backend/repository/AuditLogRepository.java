package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.repository.Repository;
import uz.minipolya.minipolya_backend.entity.AuditLog;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
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

    /** Entries of the given actions on several entities at once — for a list that shows each one's latest. */
    List<AuditLog> findByEntityTypeAndEntityIdInAndActionIn(String entityType, Collection<UUID> entityIds,
                                                            Collection<String> actions);

    /** Entries of the given actions on one kind of entity written after {@code after} — recent history. */
    List<AuditLog> findByEntityTypeAndActionInAndCreatedAtAfter(String entityType, Collection<String> actions,
                                                               OffsetDateTime after);
}
