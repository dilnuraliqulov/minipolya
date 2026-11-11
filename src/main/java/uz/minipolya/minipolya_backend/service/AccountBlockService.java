package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.dto.response.BlockedAccountResponse;
import uz.minipolya.minipolya_backend.repository.AuditLogRepository;

import java.util.UUID;

/**
 * Why and when a user was blocked, for the blocked-account 403 the player sees. Read from
 * the audit log: the reason an admin types with a block is stored only there.
 */
@Service
@RequiredArgsConstructor
public class AccountBlockService {

    private final AuditLogRepository auditLogRepository;

    /** The latest block's reason and time; both fields null when no block was recorded. */
    @Transactional(readOnly = true)
    public BlockedAccountResponse details(UUID userId) {
        return auditLogRepository.findFirstByEntityTypeAndEntityIdAndActionOrderByCreatedAtDescIdDesc(
                        AuditEntityType.USER, userId, AuditAction.USER_BLOCKED.name())
                .map(entry -> BlockedAccountResponse.builder()
                        .reason(entry.getReason() == null || entry.getReason().isBlank() ? null : entry.getReason())
                        .blockedAt(entry.getCreatedAt())
                        .build())
                .orElseGet(BlockedAccountResponse::new);
    }
}
