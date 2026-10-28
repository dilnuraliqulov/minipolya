package uz.minipolya.minipolya_backend.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditActorType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.dto.response.AuditLogResponse;
import uz.minipolya.minipolya_backend.entity.AuditLog;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.repository.AuditLogFilter;
import uz.minipolya.minipolya_backend.repository.AuditLogRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Append-only audit trail (CLAUDE.md, "Audit log"). {@link #record} is fire-and-forget:
 * it never throws, so audit logging can never break the business flow it records.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final int LABEL_MAX = 255;

    private final AuditLogRepository auditLogRepository;
    private final UserRepository userRepository;
    private final AuditLogResponseFactory responseFactory;
    private final ObjectMapper objectMapper;

    /**
     * Stores one entry. The actor's name is saved as it is now; an ADMIN entry without
     * an explicit IP takes the current request's.
     */
    public void record(AuditEntry entry) {
        try {
            User actor = entry.getActorId() == null ? null
                    : userRepository.findById(entry.getActorId()).orElse(null);
            auditLogRepository.save(AuditLog.builder()
                    .user(actor)
                    .actorType(entry.getActorType().name())
                    .actorName(actor == null ? null : actor.getName())
                    .action(entry.getAction().name())
                    .entityType(entry.getEntityType())
                    .entityId(entry.getEntityId())
                    .entityLabel(truncate(entry.getEntityLabel()))
                    .changes(entry.getChanges().isEmpty() ? null
                            : objectMapper.writeValueAsString(entry.getChanges()))
                    .reason(entry.getReason())
                    .ipAddress(entry.getIp() != null ? entry.getIp()
                            : entry.getActorType() == AuditActorType.ADMIN ? currentRequestIp() : null)
                    .build());
        } catch (Exception ex) {
            log.error("Audit log failed for action {} on {} {}: {}",
                    entry.getAction(), entry.getEntityType(), entry.getEntityId(), ex.toString());
        }
    }

    // ---- Reading ----

    /**
     * The admin search, newest first.
     *
     * @param from first Tashkent calendar day included, or null
     * @param to   last Tashkent calendar day included, or null
     */
    @Transactional(readOnly = true)
    public Page<AuditLogResponse> search(String actor, String actorType, List<String> actions,
                                         String entityType, UUID entityId,
                                         LocalDate from, LocalDate to, Pageable pageable) {
        if (from != null && to != null && to.isBefore(from)) {
            throw new BadRequestException("to must be on or after from");
        }
        AuditLogFilter filter = AuditLogFilter.builder()
                .actor(actor)
                .actorType(parseActorType(actorType))
                .actions(normaliseActions(actions))
                .entityType(blankToNull(entityType))
                .entityId(entityId)
                .from(from == null ? null : from.atStartOfDay(TASHKENT).toOffsetDateTime())
                .to(to == null ? null : to.plusDays(1).atStartOfDay(TASHKENT).toOffsetDateTime())
                .build();
        return auditLogRepository.search(filter, pageable).map(responseFactory::of);
    }

    /** Every action code, for the admin panel's filter dropdown. */
    public List<String> actionCodes() {
        return Arrays.stream(AuditAction.values()).map(Enum::name).toList();
    }

    // ---- Helpers ----

    private String parseActorType(String actorType) {
        if (actorType == null || actorType.isBlank()) {
            return null;
        }
        try {
            return AuditActorType.valueOf(actorType.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("actorType must be one of ADMIN, OWNER, PLAYER, SYSTEM");
        }
    }

    /**
     * Upper-cased and de-duplicated. Unknown codes are allowed on purpose: rows written
     * before AuditAction existed carry codes it no longer lists.
     */
    private List<String> normaliseActions(List<String> actions) {
        if (actions == null) {
            return List.of();
        }
        return actions.stream()
                .filter(a -> a != null && !a.isBlank())
                .map(a -> a.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private static String currentRequestIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }

    private static String truncate(String label) {
        return label == null || label.length() <= LABEL_MAX ? label : label.substring(0, LABEL_MAX - 1) + "…";
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
