package uz.minipolya.minipolya_backend.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import uz.minipolya.minipolya_backend.entity.AuditLog;

/** The admin search, newest first. Read-only: there is no way to change or delete an entry. */
public interface AuditLogRepositoryCustom {

    Page<AuditLog> search(AuditLogFilter filter, Pageable pageable);
}
