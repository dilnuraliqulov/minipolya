package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tools.jackson.databind.ObjectMapper;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditChange;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.dto.response.AuditLogResponse;
import uz.minipolya.minipolya_backend.entity.AuditLog;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.repository.AuditLogFilter;
import uz.minipolya.minipolya_backend.repository.AuditLogRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID STADIUM_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    private AuditLogRepository auditLogRepository;
    private UserRepository userRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private AuditService service;

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        userRepository = mock(UserRepository.class);
        service = new AuditService(auditLogRepository, userRepository,
                new AuditLogResponseFactory(objectMapper), objectMapper);
        when(auditLogRepository.save(any(AuditLog.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private AuditLog saved() {
        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }

    // ---- Writing ----

    @Test
    void recordsActorEntityChangesAndReason() {
        User admin = User.builder().id(ADMIN_ID).name("Dilnur Aliqulov").isAdmin(true).build();
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        service.record(AuditEntry.admin(ADMIN_ID, AuditAction.STADIUM_BLOCKED)
                .entity(AuditEntityType.STADIUM, STADIUM_ID, "Chilonzor Arena")
                .change("status", "approved", "blocked")
                .reason("Fake photos")
                .ip("203.0.113.7"));

        AuditLog row = saved();
        assertThat(row.getUser()).isSameAs(admin);
        assertThat(row.getActorType()).isEqualTo("ADMIN");
        assertThat(row.getActorName()).isEqualTo("Dilnur Aliqulov");
        assertThat(row.getAction()).isEqualTo("STADIUM_BLOCKED");
        assertThat(row.getEntityType()).isEqualTo("stadium");
        assertThat(row.getEntityId()).isEqualTo(STADIUM_ID);
        assertThat(row.getEntityLabel()).isEqualTo("Chilonzor Arena");
        assertThat(row.getChanges())
                .isEqualTo("[{\"field\":\"status\",\"oldValue\":\"approved\",\"newValue\":\"blocked\"}]");
        assertThat(row.getReason()).isEqualTo("Fake photos");
        assertThat(row.getIpAddress()).isEqualTo("203.0.113.7");
        assertThat(row.getDetails()).isNull();
    }

    @Test
    void aSystemEntryHasNoUserAndNoIp() {
        service.record(AuditEntry.system(AuditAction.BOOKING_EXPIRED)
                .entity(AuditEntityType.BOOKING, UUID.randomUUID(), "Chilonzor Arena, 2026-11-02 20:00 (1h)")
                .change("status", "pending", "expired"));

        AuditLog row = saved();
        assertThat(row.getUser()).isNull();
        assertThat(row.getActorType()).isEqualTo("SYSTEM");
        assertThat(row.getActorName()).isNull();
        assertThat(row.getIpAddress()).isNull();
        verify(userRepository, never()).findById(any());
    }

    @Test
    void anAdminEntryTakesTheRequestIpWhenNoneIsGiven() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.4");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record(AuditEntry.admin(ADMIN_ID, AuditAction.USER_BLOCKED));

        assertThat(saved().getIpAddress()).isEqualTo("198.51.100.4");
    }

    @Test
    void aPlayerEntryDoesNotRecordTheIp() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("198.51.100.4");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        service.record(AuditEntry.player(UUID.randomUUID(), AuditAction.BOOKING_CREATED));

        assertThat(saved().getIpAddress()).isNull();
    }

    @Test
    void anEntryWithoutChangesStoresNoChangesJson() {
        service.record(AuditEntry.system(AuditAction.GAME_CONFIRMED));

        assertThat(saved().getChanges()).isNull();
    }

    @Test
    void anOverlongLabelIsCutTo255Characters() {
        service.record(AuditEntry.system(AuditAction.GAME_CONFIRMED)
                .entity(AuditEntityType.GAME, UUID.randomUUID(), "x".repeat(400)));

        assertThat(saved().getEntityLabel()).hasSize(255).endsWith("…");
    }

    @Test
    void recordNeverThrows() {
        when(auditLogRepository.save(any(AuditLog.class))).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> service.record(AuditEntry.system(AuditAction.BOOKING_EXPIRED)))
                .doesNotThrowAnyException();
    }

    // ---- The entry builder ----

    @Test
    void changeKeepsOnlyRealChangesAndNormalisesValues() {
        AuditEntry entry = AuditEntry.owner(UUID.randomUUID(), AuditAction.STADIUM_UPDATED)
                .change("name", "Arena", "Arena")
                .change("pricePerHour", 15_000_000, 18_000_000L)
                .change("openTime", LocalTime.of(8, 0), LocalTime.of(9, 0))
                .change("minBookingAmount", null, 5_000_000L);

        assertThat(entry.getChanges()).containsExactly(
                new AuditChange("pricePerHour", 15_000_000L, 18_000_000L),
                new AuditChange("openTime", "08:00", "09:00"),
                new AuditChange("minBookingAmount", null, 5_000_000L));
    }

    @Test
    void secretLookingFieldsAreNeverKept() {
        AuditEntry entry = AuditEntry.admin(ADMIN_ID, AuditAction.USER_ROLE_CHANGED)
                .change("passwordHash", "a", "b")
                .change("cardNumber", "8600123412341234", "x")
                .change("providerToken", "t1", "t2")
                .change("isAdmin", false, true);

        assertThat(entry.getChanges()).extracting(AuditChange::field).containsExactly("isAdmin");
    }

    @Test
    void diffListsEveryFieldThatDiffers() {
        AuditEntry entry = AuditEntry.owner(UUID.randomUUID(), AuditAction.STADIUM_UPDATED)
                .diff(java.util.Map.of("name", "A", "phone", "1"), java.util.Map.of("name", "B", "phone", "1"));

        assertThat(entry.getChanges()).containsExactly(new AuditChange("name", "A", "B"));
    }

    // ---- Reading ----

    private AuditLogFilter searched() {
        ArgumentCaptor<AuditLogFilter> captor = ArgumentCaptor.forClass(AuditLogFilter.class);
        verify(auditLogRepository).search(captor.capture(), any(Pageable.class));
        return captor.getValue();
    }

    @Test
    void searchTurnsTashkentDaysIntoAHalfOpenUtcRange() {
        when(auditLogRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.search(null, null, null, null, null,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 2), PageRequest.of(0, 20));

        AuditLogFilter filter = searched();
        // Tashkent is UTC+5: 1 Nov 00:00 local is 31 Oct 19:00 UTC; "to" is the next midnight.
        assertThat(filter.from()).isEqualTo(OffsetDateTime.parse("2026-11-01T00:00+05:00"));
        assertThat(filter.to()).isEqualTo(OffsetDateTime.parse("2026-11-03T00:00+05:00"));
    }

    @Test
    void searchPassesEveryFilterOnNormalised() {
        when(auditLogRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of()));
        UUID entityId = UUID.randomUUID();

        service.search("  dilnur ", "admin", List.of("stadium_blocked", "STADIUM_BLOCKED", " user_blocked"),
                " stadium ", entityId, null, null, PageRequest.of(0, 20));

        AuditLogFilter filter = searched();
        assertThat(filter.actor()).isEqualTo("  dilnur ");
        assertThat(filter.actorType()).isEqualTo("ADMIN");
        assertThat(filter.actions()).containsExactly("STADIUM_BLOCKED", "USER_BLOCKED");
        assertThat(filter.entityType()).isEqualTo("stadium");
        assertThat(filter.entityId()).isEqualTo(entityId);
        assertThat(filter.from()).isNull();
        assertThat(filter.to()).isNull();
    }

    @Test
    void anUnknownActorTypeIs400() {
        assertThatThrownBy(() -> service.search(null, "robot", null, null, null, null, null, PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("ADMIN, OWNER, PLAYER, SYSTEM");
    }

    @Test
    void aRangeEndingBeforeItStartsIs400() {
        assertThatThrownBy(() -> service.search(null, null, null, null, null,
                LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 1), PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void searchResultsUseTheAdminPanelShape() {
        User admin = User.builder().id(ADMIN_ID).name("Renamed Later").build();
        AuditLog row = AuditLog.builder()
                .id(UUID.randomUUID()).user(admin).actorType("ADMIN").actorName("Dilnur Aliqulov")
                .action("SETTINGS_CHANGED").entityType("platform_settings").entityId(STADIUM_ID)
                .entityLabel("Platform settings")
                .changes("[{\"field\":\"teamGamePlatformFee\",\"oldValue\":2500000,\"newValue\":3000000}]")
                .ipAddress("203.0.113.7").createdAt(OffsetDateTime.parse("2026-10-28T10:00:00Z"))
                .build();
        when(auditLogRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(row)));

        AuditLogResponse item = service.search(null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .getContent().getFirst();

        // The name saved at the time, not the user's current one.
        assertThat(item.getActor()).isEqualTo(new AuditLogResponse.Actor(ADMIN_ID, "Dilnur Aliqulov", "ADMIN"));
        assertThat(item.getEntity())
                .isEqualTo(new AuditLogResponse.Entity("platform_settings", STADIUM_ID, "Platform settings"));
        assertThat(item.getChanges())
                .containsExactly(new AuditChange("teamGamePlatformFee", 2_500_000L, 3_000_000L));
        assertThat(item.getTime()).isEqualTo(OffsetDateTime.parse("2026-10-28T10:00:00Z"));
        assertThat(item.getIp()).isEqualTo("203.0.113.7");
    }

    @Test
    void anOldRowShowsWhatItHas() {
        AuditLog legacy = AuditLog.builder()
                .id(UUID.randomUUID()).action("PLATFORM_SETTINGS_UPDATED").entityType("platform_settings")
                .details("{\"teamGamePlatformFee\":{\"old\":2500000,\"new\":3000000}}")
                .createdAt(OffsetDateTime.parse("2026-10-01T10:00:00Z"))
                .build();
        when(auditLogRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of(legacy)));

        AuditLogResponse item = service.search(null, null, null, null, null, null, null, PageRequest.of(0, 20))
                .getContent().getFirst();

        assertThat(item.getActor()).isNull();
        assertThat(item.getChanges()).isEmpty();
        assertThat(item.getDetails()).contains("teamGamePlatformFee");
    }

    @Test
    void actionCodesListEveryAction() {
        assertThat(service.actionCodes())
                .hasSize(AuditAction.values().length)
                .contains("STADIUM_APPROVED", "SETTINGS_CHANGED", "BOOKING_EXPIRED", "ADMIN_LOGIN_FAILED");
    }

    @Test
    void searchWithoutFiltersPassesAnEmptyFilter() {
        when(auditLogRepository.search(any(), any())).thenReturn(new PageImpl<>(List.of()));

        service.search(null, null, null, null, null, null, null, PageRequest.of(0, 20));

        verify(auditLogRepository).search(eq(AuditLogFilter.builder().actions(List.of()).build()), any());
    }
}
