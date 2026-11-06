package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.dto.request.UpdatePlatformSettingsRequest;
import uz.minipolya.minipolya_backend.dto.response.PlatformSettingsResponse;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.mapper.PlatformSettingsMapperImpl;
import uz.minipolya.minipolya_backend.repository.PlatformSettingsRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T1/T2: the platform's default minimum booking amount — every stadium without its own uses
 * it — and the rule that a minimum is always more than the team-game fee.
 */
class DefaultMinimumSettingsTest {

    private static final UUID ADMIN_ID = UUID.randomUUID();

    private PlatformSettingsRepository repository;
    private AuditService auditService;
    private PlatformSettingsService service;
    private PlatformSettings row;

    @BeforeEach
    void setUp() {
        repository = mock(PlatformSettingsRepository.class);
        auditService = mock(AuditService.class);
        service = new PlatformSettingsService(repository, new PlatformSettingsMapperImpl(), auditService);
        row = PlatformSettings.builder().id(UUID.randomUUID()).build();
        when(repository.findAll()).thenReturn(List.of(row));
        when(repository.save(any(PlatformSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void theDefaultIsFiftyThousandSoum() {
        assertThat(PlatformSettings.builder().build().getDefaultMinBookingAmount()).isEqualTo(5_000_000L);
        assertThat(service.get().getDefaultMinBookingAmount()).isEqualTo(5_000_000L);
    }

    @Test
    void anAdminChangesTheDefaultAndItIsAudited() {
        PlatformSettingsResponse response = service.update(
                UpdatePlatformSettingsRequest.builder().defaultMinBookingAmount(6_000_000L).build(), ADMIN_ID);

        assertThat(response.getDefaultMinBookingAmount()).isEqualTo(6_000_000L);
        ArgumentCaptor<AuditEntry> audit = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditService).record(audit.capture());
        assertThat(audit.getValue().getChanges()).anySatisfy(c -> {
            assertThat(c.field()).isEqualTo("defaultMinBookingAmount");
            assertThat(c.oldValue()).isEqualTo(5_000_000L);
            assertThat(c.newValue()).isEqualTo(6_000_000L);
        });
    }

    @Test
    void aDefaultAtOrBelowTheTeamFeeIsRefused() {
        assertThatThrownBy(() -> service.update(
                UpdatePlatformSettingsRequest.builder().defaultMinBookingAmount(2_500_000L).build(), ADMIN_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("defaultMinBookingAmount must be more than the team game platform fee (25 000 so'm)");
        verify(repository, never()).save(any());
    }

    @Test
    void aTeamFeeAtOrAboveTheDefaultIsRefused() {
        assertThatThrownBy(() -> service.update(
                UpdatePlatformSettingsRequest.builder().teamGamePlatformFee(5_000_000L).build(), ADMIN_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("teamGamePlatformFee must be less than the default minimum booking amount (50 000 so'm)");
        verify(repository, never()).save(any());
    }

    @Test
    void bothChangedTogetherAreCheckedAsTheyWouldBe() {
        PlatformSettingsResponse response = service.update(UpdatePlatformSettingsRequest.builder()
                .teamGamePlatformFee(6_000_000L).defaultMinBookingAmount(8_000_000L).build(), ADMIN_ID);

        assertThat(response.getTeamGamePlatformFee()).isEqualTo(6_000_000L);
        assertThat(response.getDefaultMinBookingAmount()).isEqualTo(8_000_000L);
    }

    @Test
    void theEffectiveMinimumIsTheStadiumsOwnOrTheDefault() {
        CardPricing pricing = new CardPricing(() -> row);

        assertThat(pricing.effectiveMinimum(null)).isEqualTo(5_000_000L);
        assertThat(pricing.effectiveMinimum(7_000_000L)).isEqualTo(7_000_000L);
        // A stadium value not above the team fee is ignored at runtime: the default is used.
        assertThat(pricing.effectiveMinimum(2_500_000L)).isEqualTo(5_000_000L);
        assertThat(pricing.effectiveMinimum(1_000_000L)).isEqualTo(5_000_000L);
        assertThat(pricing.effectiveMinimum(2_500_001L)).isEqualTo(2_500_001L);
    }
}
