package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.dto.request.UpdatePlatformSettingsRequest;
import uz.minipolya.minipolya_backend.dto.response.PlatformSettingsResponse;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.mapper.PlatformSettingsMapper;
import uz.minipolya.minipolya_backend.mapper.PlatformSettingsMapperImpl;
import uz.minipolya.minipolya_backend.repository.PlatformSettingsRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformSettingsServiceTest {

    private static final UUID SETTINGS_ID = UUID.randomUUID();
    private static final UUID ADMIN_ID = UUID.randomUUID();

    private PlatformSettingsRepository repository;
    private AuditService auditService;
    private PlatformSettingsService service;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformSettingsRepository.class);
        auditService = mock(AuditService.class);
        PlatformSettingsMapper mapper = new PlatformSettingsMapperImpl();
        service = new PlatformSettingsService(repository, mapper, auditService);

        when(repository.findAll()).thenReturn(List.of(settings()));
        when(repository.save(any(PlatformSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** The row seeded by changeset 021. */
    private PlatformSettings settings() {
        return PlatformSettings.builder()
                .id(SETTINGS_ID)
                .bookingCommissionPercent(0)
                .teamGamePlatformFee(2_500_000L)
                .casualGameServiceFee(0L)
                .build();
    }

    @Test
    void readsTheSeededDefaults() {
        PlatformSettingsResponse response = service.get();

        assertThat(response.getBookingCommissionPercent()).isZero();
        assertThat(response.getTeamGamePlatformFee()).isEqualTo(2_500_000L);
        assertThat(response.getCasualGameServiceFee()).isZero();
    }

    @Test
    void updateAppliesOnlyTheProvidedFields() {
        PlatformSettingsResponse response = service.update(
                UpdatePlatformSettingsRequest.builder().teamGamePlatformFee(3_000_000L).build(), ADMIN_ID);

        assertThat(response.getTeamGamePlatformFee()).isEqualTo(3_000_000L);
        assertThat(response.getBookingCommissionPercent()).isZero();
        assertThat(response.getCasualGameServiceFee()).isZero();
    }

    @Test
    void rejectsCommissionAboveFifty() {
        assertThatThrownBy(() -> service.update(
                UpdatePlatformSettingsRequest.builder().bookingCommissionPercent(51).build(), ADMIN_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("bookingCommissionPercent");

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsNegativeCommission() {
        assertThatThrownBy(() -> service.update(
                UpdatePlatformSettingsRequest.builder().bookingCommissionPercent(-1).build(), ADMIN_ID))
                .isInstanceOf(BadRequestException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void acceptsCommissionAtBothEndsOfTheRange() {
        assertThat(service.update(UpdatePlatformSettingsRequest.builder().bookingCommissionPercent(0).build(),
                ADMIN_ID).getBookingCommissionPercent()).isZero();
        assertThat(service.update(UpdatePlatformSettingsRequest.builder().bookingCommissionPercent(50).build(),
                ADMIN_ID).getBookingCommissionPercent()).isEqualTo(50);
    }

    @Test
    void rejectsNegativeTeamGameFee() {
        assertThatThrownBy(() -> service.update(
                UpdatePlatformSettingsRequest.builder().teamGamePlatformFee(-1L).build(), ADMIN_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("teamGamePlatformFee");

        verify(repository, never()).save(any());
    }

    @Test
    void rejectsNegativeCasualGameFee() {
        assertThatThrownBy(() -> service.update(
                UpdatePlatformSettingsRequest.builder().casualGameServiceFee(-1L).build(), ADMIN_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("casualGameServiceFee");

        verify(repository, never()).save(any());
    }

    @Test
    void updateAuditsEveryChangedFieldWithItsOldAndNewValue() {
        service.update(UpdatePlatformSettingsRequest.builder()
                .bookingCommissionPercent(5)
                .teamGamePlatformFee(3_000_000L)
                .build(), ADMIN_ID);

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService).log(eq(ADMIN_ID), eq("PLATFORM_SETTINGS_UPDATED"), eq("platform_settings"),
                eq(SETTINGS_ID), details.capture());

        assertThat(details.getValue())
                .contains("\"bookingCommissionPercent\":{\"old\":0,\"new\":5}")
                .contains("\"teamGamePlatformFee\":{\"old\":2500000,\"new\":3000000}")
                // Untouched fields are not reported as changes.
                .doesNotContain("casualGameServiceFee");
    }

    @Test
    void updateThatChangesNothingIsNotAudited() {
        service.update(UpdatePlatformSettingsRequest.builder().bookingCommissionPercent(0).build(), ADMIN_ID);

        verify(auditService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void readingSeedsTheRowWhenTheTableIsEmpty() {
        when(repository.findAll()).thenReturn(List.of());

        PlatformSettingsResponse response = service.get();

        assertThat(response.getBookingCommissionPercent()).isZero();
        assertThat(response.getTeamGamePlatformFee()).isEqualTo(2_500_000L);
        assertThat(response.getCasualGameServiceFee()).isZero();
        verify(repository).save(any(PlatformSettings.class));
    }
}
