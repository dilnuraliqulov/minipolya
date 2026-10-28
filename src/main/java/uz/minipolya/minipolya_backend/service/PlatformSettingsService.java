package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.dto.request.UpdatePlatformSettingsRequest;
import uz.minipolya.minipolya_backend.dto.response.PlatformSettingsResponse;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.mapper.PlatformSettingsMapper;
import uz.minipolya.minipolya_backend.repository.PlatformSettingsRepository;

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
        AuditEntry audit = AuditEntry.admin(adminUserId, AuditAction.SETTINGS_CHANGED);

        Integer commission = request.getBookingCommissionPercent();
        if (commission != null && commission != settings.getBookingCommissionPercent()) {
            audit.change("bookingCommissionPercent", settings.getBookingCommissionPercent(), commission);
            settings.setBookingCommissionPercent(commission);
        }

        Long teamFee = request.getTeamGamePlatformFee();
        if (teamFee != null && teamFee != settings.getTeamGamePlatformFee()) {
            audit.change("teamGamePlatformFee", settings.getTeamGamePlatformFee(), teamFee);
            settings.setTeamGamePlatformFee(teamFee);
        }

        Long casualFee = request.getCasualGameServiceFee();
        if (casualFee != null && casualFee != settings.getCasualGameServiceFee()) {
            audit.change("casualGameServiceFee", settings.getCasualGameServiceFee(), casualFee);
            settings.setCasualGameServiceFee(casualFee);
        }

        Integer cardFeeBps = request.getCardFeeBps();
        if (cardFeeBps != null && cardFeeBps != settings.getCardFeeBps()) {
            audit.change("cardFeeBps", settings.getCardFeeBps(), cardFeeBps);
            settings.setCardFeeBps(cardFeeBps);
        }

        Long roundingStep = request.getPaymentRoundingStep();
        if (roundingStep != null && roundingStep != settings.getPaymentRoundingStep()) {
            audit.change("paymentRoundingStep", settings.getPaymentRoundingStep(), roundingStep);
            settings.setPaymentRoundingStep(roundingStep);
        }

        if (audit.getChanges().isEmpty()) {
            return mapper.toResponse(settings);
        }

        PlatformSettings saved = repository.save(settings);
        auditService.record(audit.entity(AuditEntityType.PLATFORM_SETTINGS, saved.getId(), "Platform settings"));
        log.info("Platform settings updated by admin {}: {}", adminUserId, audit.getChanges());
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
        Integer bps = request.getCardFeeBps();
        if (bps != null && (bps < 0 || bps > PlatformSettings.MAX_CARD_FEE_BPS)) {
            throw new BadRequestException("cardFeeBps must be between 0 and " + PlatformSettings.MAX_CARD_FEE_BPS);
        }
        Long step = request.getPaymentRoundingStep();
        if (step != null && (step < 1 || step > PlatformSettings.MAX_PAYMENT_ROUNDING_STEP)) {
            throw new BadRequestException("paymentRoundingStep must be between 1 and "
                    + PlatformSettings.MAX_PAYMENT_ROUNDING_STEP);
        }
    }

    private void requireNotNegative(String field, Long value) {
        if (value != null && value < 0) {
            throw new BadRequestException(field + " must not be negative");
        }
    }
}
