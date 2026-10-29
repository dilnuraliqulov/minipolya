package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import uz.minipolya.minipolya_backend.audit.AuditChange;
import uz.minipolya.minipolya_backend.dto.response.AuditLogResponse;
import uz.minipolya.minipolya_backend.entity.AuditLog;
import uz.minipolya.minipolya_backend.entity.User;

import java.util.List;

/**
 * Turns a stored entry into the admin panel's shape. Not a MapStruct mapper because the
 * {@code changes} column is JSON that has to be parsed (the same reason
 * BookingResponseFactory exists). Old rows show whatever they have: no actor type, no
 * changes, and their legacy {@code details}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditLogResponseFactory {

    private static final TypeReference<List<AuditChange>> CHANGES = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public AuditLogResponse of(AuditLog entry) {
        return AuditLogResponse.builder()
                .id(entry.getId())
                .time(entry.getCreatedAt())
                .actor(actor(entry))
                .action(entry.getAction())
                .entity(AuditLogResponse.Entity.builder()
                        .type(entry.getEntityType())
                        .id(entry.getEntityId())
                        .label(entry.getEntityLabel())
                        .build())
                .changes(changes(entry))
                .reason(entry.getReason())
                .ip(entry.getIpAddress())
                .details(entry.getDetails())
                .build();
    }

    private AuditLogResponse.Actor actor(AuditLog entry) {
        User user = entry.getUser();
        if (user == null && entry.getActorType() == null) {
            return null; // an old row written by the system
        }
        return AuditLogResponse.Actor.builder()
                .id(user == null ? null : user.getId())
                // The name saved at the time; old rows fall back to the current one.
                .name(entry.getActorName() != null ? entry.getActorName() : user == null ? null : user.getName())
                .type(entry.getActorType())
                .build();
    }

    /** The entry's field changes, read from their JSON; empty when there are none or they cannot be read. */
    public List<AuditChange> changes(AuditLog entry) {
        if (entry.getChanges() == null || entry.getChanges().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(entry.getChanges(), CHANGES);
        } catch (Exception ex) {
            log.warn("Unreadable audit changes on entry {}: {}", entry.getId(), ex.toString());
            return List.of();
        }
    }
}
