package uz.minipolya.minipolya_backend.repository;

import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Filters for the admin audit log search. Every field is optional; the ones given are
 * combined with AND.
 *
 * @param actor      matched case-insensitively against the actor's current name,
 *                   username and phone, and the name saved on the entry
 * @param actorType  ADMIN, OWNER, PLAYER or SYSTEM
 * @param actions    any of these action codes
 * @param from       inclusive
 * @param to         exclusive
 */
@Builder
public record AuditLogFilter(String actor,
                             String actorType,
                             List<String> actions,
                             String entityType,
                             UUID entityId,
                             OffsetDateTime from,
                             OffsetDateTime to) {
}
