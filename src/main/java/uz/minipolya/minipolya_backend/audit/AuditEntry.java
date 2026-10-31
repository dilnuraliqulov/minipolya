package uz.minipolya.minipolya_backend.audit;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One audit entry, built at the call site and handed to {@code AuditService.record}:
 *
 * <pre>{@code
 * auditService.record(AuditEntry.admin(adminId, AuditAction.STADIUM_BLOCKED)
 *         .entity(AuditEntityType.STADIUM, stadium.getId(), stadium.getName())
 *         .change("status", "approved", "blocked")
 *         .reason(reason));
 * }</pre>
 *
 * <p>{@link #change} only keeps a real change (old and new differ once normalised), so a
 * caller can pass every field it might have touched. Fields whose name looks like a
 * secret — password, hash, token, card number, secret — are never kept.
 */
@Slf4j
@Getter
public final class AuditEntry {

    private static final Pattern SECRET_FIELD =
            Pattern.compile("(?i).*(password|hash|token|card_?number|cvv|secret|jwt).*");

    private final AuditActorType actorType;
    private final UUID actorId;
    private final AuditAction action;
    private String entityType;
    private UUID entityId;
    private String entityLabel;
    private final List<AuditChange> changes = new ArrayList<>();
    private String reason;
    private String ip;

    private AuditEntry(AuditActorType actorType, UUID actorId, AuditAction action) {
        this.actorType = actorType;
        this.actorId = actorId;
        this.action = action;
    }

    public static AuditEntry admin(UUID adminUserId, AuditAction action) {
        return new AuditEntry(AuditActorType.ADMIN, adminUserId, action);
    }

    public static AuditEntry owner(UUID ownerUserId, AuditAction action) {
        return new AuditEntry(AuditActorType.OWNER, ownerUserId, action);
    }

    public static AuditEntry player(UUID userId, AuditAction action) {
        return new AuditEntry(AuditActorType.PLAYER, userId, action);
    }

    /** Scheduled jobs and automatic consequences: no user. */
    public static AuditEntry system(AuditAction action) {
        return new AuditEntry(AuditActorType.SYSTEM, null, action);
    }

    /** The thing acted on, with a human label saved now so the entry survives renames. */
    public AuditEntry entity(String type, UUID id, String label) {
        this.entityType = type;
        this.entityId = id;
        this.entityLabel = label;
        return this;
    }

    /** Records {@code field: oldValue → newValue}, unless nothing actually changed. */
    public AuditEntry change(String field, Object oldValue, Object newValue) {
        if (SECRET_FIELD.matcher(field).matches()) {
            log.error("Refusing to audit a secret-looking field '{}' on {}", field, action);
            return this;
        }
        AuditChange change = AuditChange.of(field, oldValue, newValue);
        if (change.isChange()) {
            changes.add(change);
        }
        return this;
    }

    /** A value that is new: {@code null → value}. */
    public AuditEntry created(String field, Object value) {
        return change(field, null, value);
    }

    /**
     * Every field that differs between two snapshots of the same thing, in the order the
     * {@code before} map lists them (then any keys only {@code after} has).
     */
    public AuditEntry diff(Map<String, ?> before, Map<String, ?> after) {
        Set<String> keys = new LinkedHashSet<>(before.keySet());
        keys.addAll(after.keySet());
        for (String key : keys) {
            change(key, before.get(key), after.get(key));
        }
        return this;
    }

    public AuditEntry reason(String reason) {
        this.reason = reason == null || reason.isBlank() ? null : reason.trim();
        return this;
    }

    /** Only needed off-request; for an ADMIN entry AuditService reads the request's IP itself. */
    public AuditEntry ip(String ip) {
        this.ip = ip;
        return this;
    }

    public List<AuditChange> getChanges() {
        return Collections.unmodifiableList(changes);
    }
}
