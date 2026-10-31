package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Append-only record of an important action: who did it ({@code user} plus
 * {@code actorType}, with {@code actorName} saved at the time), what ({@code action}, a
 * stable {@link uz.minipolya.minipolya_backend.audit.AuditAction} code), to what
 * ({@code entityType}, {@code entityId}, and {@code entityLabel} saved at the time),
 * exactly what changed ({@code changes}, a JSON array of {@code {field, oldValue,
 * newValue}}), why ({@code reason}) and from where ({@code ipAddress}).
 *
 * <p>{@code @Immutable}: Hibernate never issues an UPDATE for a stored row, and
 * AuditLogRepository exposes no delete. {@code details} holds the free-form JSON of rows
 * written before the structured columns existed (changeset 029); new entries leave it null.
 */
@Entity
@Table(name = "audit_log")
@Immutable
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditLog {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Null for SYSTEM actions, and for a sign-in attempt with an unknown username. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** ADMIN, OWNER, PLAYER or SYSTEM; null only on rows written before changeset 029. */
    @Column(name = "actor_type", length = 10)
    private String actorType;

    /** The actor's name when the entry was written. */
    @Column(name = "actor_name", length = 120)
    private String actorName;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    /** Null when the action concerns no stored entity, e.g. a sign-in with an unknown username. */
    @Column(name = "entity_id")
    private UUID entityId;

    @Column(name = "entity_label", length = 255)
    private String entityLabel;

    /** JSON array of {@code {field, oldValue, newValue}}; null when the action changed no field. */
    @Column(columnDefinition = "text")
    private String changes;

    @Column(columnDefinition = "text")
    private String reason;

    /** Legacy free-form JSON, only on rows written before changeset 029. */
    @Column(columnDefinition = "text")
    private String details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
