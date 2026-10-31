package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.audit.AuditChange;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * One audit entry for the admin panel: {@code id, time, actor {id, name, type}, action,
 * entity {type, id, label}, changes [{field, oldValue, newValue}], reason, ip}.
 * {@code changes} is always an array (empty when nothing changed or for an old row);
 * {@code details} appears only on rows written before the structured columns existed.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AuditLogResponse {

    private UUID id;
    private OffsetDateTime time;
    private Actor actor;
    private String action;
    private Entity entity;

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private List<AuditChange> changes;

    private String reason;
    private String ip;
    /** Legacy free-form JSON; absent on every entry written with the structured columns. */
    private String details;

    /** {@code id} and {@code name} are absent for SYSTEM; {@code type} is absent only on old rows. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Actor {
        private UUID id;
        private String name;
        private String type;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Entity {
        private String type;
        private UUID id;
        private String label;
    }
}
