package uz.minipolya.minipolya_backend.audit;

import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.service.AuditService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;

/** Reads back what a test's mocked AuditService was asked to record. */
public final class Audited {

    private Audited() {
    }

    /** Every entry recorded so far, in order. */
    public static List<AuditEntry> entries(AuditService auditService) {
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService, atLeast(0)).record(captor.capture());
        return captor.getAllValues();
    }

    /** The entries with this action. */
    public static List<AuditEntry> entries(AuditService auditService, AuditAction action) {
        return entries(auditService).stream().filter(e -> e.getAction() == action).toList();
    }

    /** The single entry with this action; fails unless there is exactly one. */
    public static AuditEntry one(AuditService auditService, AuditAction action) {
        List<AuditEntry> matching = entries(auditService, action);
        assertThat(matching).as("audit entries with action %s", action).hasSize(1);
        return matching.getFirst();
    }
}
