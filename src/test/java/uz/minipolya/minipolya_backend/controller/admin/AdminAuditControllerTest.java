package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uz.minipolya.minipolya_backend.exception.GlobalExceptionHandler;
import uz.minipolya.minipolya_backend.service.AuditService;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** How the query string reaches the service. The search itself is in AuditServiceTest. */
class AdminAuditControllerTest {

    private final AuditService auditService = mock(AuditService.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new AdminAuditController(auditService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        when(auditService.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
    }

    @Test
    void everyFilterIsPassedThrough() throws Exception {
        UUID entityId = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

        mvc.perform(get("/api/admin/audit-log")
                        .param("actor", "dilnur")
                        .param("actorType", "ADMIN")
                        .param("action", "STADIUM_BLOCKED", "USER_BLOCKED")
                        .param("entityType", "stadium")
                        .param("entityId", entityId.toString())
                        .param("from", "2026-11-01")
                        .param("to", "2026-11-30")
                        .param("page", "1")
                        .param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content").isArray());

        verify(auditService).search(eq("dilnur"), eq("ADMIN"), eq(List.of("STADIUM_BLOCKED", "USER_BLOCKED")),
                eq("stadium"), eq(entityId), eq(LocalDate.of(2026, 11, 1)), eq(LocalDate.of(2026, 11, 30)),
                eq(org.springframework.data.domain.PageRequest.of(1, 50)));
    }

    @Test
    void severalActionsMayAlsoBeCommaSeparated() throws Exception {
        mvc.perform(get("/api/admin/audit-log").param("action", "STADIUM_BLOCKED,USER_BLOCKED"))
                .andExpect(status().isOk());

        verify(auditService).search(isNull(), isNull(), eq(List.of("STADIUM_BLOCKED", "USER_BLOCKED")),
                isNull(), isNull(), isNull(), isNull(), any());
    }

    @Test
    void aMalformedEntityIdIs400() throws Exception {
        mvc.perform(get("/api/admin/audit-log").param("entityId", "not-a-uuid"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(auditService);
    }

    @Test
    void aBadPageSizeIs400() throws Exception {
        mvc.perform(get("/api/admin/audit-log").param("size", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theActionListIsServed() throws Exception {
        when(auditService.actionCodes()).thenReturn(List.of("STADIUM_APPROVED", "SETTINGS_CHANGED"));

        mvc.perform(get("/api/admin/audit-log/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value("STADIUM_APPROVED"))
                .andExpect(jsonPath("$.data[1]").value("SETTINGS_CHANGED"));
    }

    @Test
    void entriesCannotBeChangedOrDeleted() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(put("/api/admin/audit-log/" + id)).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/admin/audit-log/" + id)).andExpect(status().is4xxClientError());
        mvc.perform(delete("/api/admin/audit-log")).andExpect(status().is4xxClientError());
    }
}
