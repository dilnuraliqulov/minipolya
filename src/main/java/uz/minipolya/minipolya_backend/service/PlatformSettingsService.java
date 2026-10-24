package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.request.UpdatePlatformSettingsRequest;
import uz.minipolya.minipolya_backend.dto.response.PlatformSettingsResponse;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.mapper.PlatformSettingsMapper;
import uz.minipolya.minipolya_backend.repository.PlatformSettingsRepository;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Reads and updates the single {@code platform_settings} row. Every money rule
 * that is not per-stadium lives here: the booking commission the platform
 * keeps, the per-team fee on a team game and the per-player service fee on a
 * casual game.
 *
 * <p>An update validates every supplied field before writing any of them, so a
 * rejected value leaves the row untouched, and records one audit entry naming
 * each changed field with its old and new value.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformSettingsService {

    private static final String AUDIT_ACTION = "PLATFORM_SETTINGS_UPDATED";
    private static final String AUDIT_ENTITY = "platform_settings";

    private final PlatformSettingsRepository repository;
    private final PlatformSettingsMapper mapper;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PlatformSettingsResponse get() {
        return mapper.toResponse(current());
    }

    /**
     * The live settings row. Seeded by changeset 021; recreated with the
     * defaults if it is ever missing, so no caller has to handle its absence.
     */
    @Transactional
    public PlatformSettings current() {
        return repository.findAll().stream()
                .findFirst()
                .orElseGet(() -> repository.save(PlatformSettings.builder().build()));
    }

    @Transactional
    public PlatformSettingsResponse update(UpdatePlatformSettingsRequest request, UUID adminUserId) {
        validate(request);

        PlatformSettings settings = current();
        List<String> changes = new ArrayList<>();

        Integer commission = request.getBookingCommissionPercent();
        if (commission != null && commission != settings.getBookingCommissionPercent()) {
            changes.add(change("bookingCommissionPercent", settings.getBookingCommissionPercent(), commission));
            settings.setBookingCommissionPercent(commission);
        }

        Long teamFee = request.getTeamGamePlatformFee();
        if (teamFee != null && teamFee != settings.getTeamGamePlatformFee()) {
            changes.add(change("teamGamePlatformFee", settings.getTeamGamePlatformFee(), teamFee));
            settings.setTeamGamePlatformFee(teamFee);
        }

        Long casualFee = request.getCasualGameServiceFee();
        if (casualFee != null && casualFee != settings.getCasualGameServiceFee()) {
            changes.add(change("casualGameServiceFee", settings.getCasualGameServiceFee(), casualFee));
            settings.setCasualGameServiceFee(casualFee);
        }

        if (changes.isEmpty()) {
            return mapper.toResponse(settings);
        }

        PlatformSettings saved = repository.save(settings);
        auditService.log(adminUserId, AUDIT_ACTION, AUDIT_ENTITY, saved.getId(),
                "{" + String.join(",", changes) + "}");
        log.info("Platform settings updated by admin {}: {}", adminUserId, changes);
        return mapper.toResponse(saved);
    }

    // ---- Helpers ----

    /** All-or-nothing: every supplied value is checked before the first write. */
    private void validate(UpdatePlatformSettingsRequest request) {
        Integer commission = request.getBookingCommissionPercent();
        if (commission != null
                && (commission < 0 || commission > PlatformSettings.MAX_BOOKING_COMMISSION_PERCENT)) {
            throw new BadRequestException("bookingCommissionPercent must be between 0 and "
                    + PlatformSettings.MAX_BOOKING_COMMISSION_PERCENT);
        }
        requireNotNegative("teamGamePlatformFee", request.getTeamGamePlatformFee());
        requireNotNegative("casualGameServiceFee", request.getCasualGameServiceFee());
    }

    private void requireNotNegative(String field, Long value) {
        if (value != null && value < 0) {
            throw new BadRequestException(field + " must not be negative");
        }
    }

    private String change(String field, long oldValue, long newValue) {
        return "\"" + field + "\":{\"old\":" + oldValue + ",\"new\":" + newValue + "}";
    }
}
