package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.request.CreatePromotionRequest;
import uz.minipolya.minipolya_backend.dto.response.PromotionResponse;
import uz.minipolya.minipolya_backend.entity.Promotion;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.PromotionMapper;
import uz.minipolya.minipolya_backend.repository.PromotionRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Owner-managed stadium discounts, applied in availability/pricing. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private final PromotionRepository promotionRepository;
    private final StadiumRepository stadiumRepository;
    private final PromotionMapper promotionMapper;
    private final AuditService auditService;

    @Transactional
    public PromotionResponse createPromotion(CreatePromotionRequest request, UUID ownerUserId) {
        Stadium stadium = requireOwnedStadium(request.getStadiumId(), ownerUserId);
        if (!request.getEndsAt().isAfter(request.getStartsAt())) {
            throw new BadRequestException("endsAt must be after startsAt");
        }
        Promotion promotion = promotionMapper.toEntity(request);
        promotion.setStadium(stadium);
        Promotion saved = promotionRepository.save(promotion);
        auditService.record(AuditEntry.owner(ownerUserId, AuditAction.PROMOTION_CREATED)
                .entity(AuditEntityType.PROMOTION, saved.getId(), AuditLabels.promotion(saved))
                .created("name", saved.getName())
                .created("discountPercent", saved.getDiscountPercent())
                .created("startsAt", saved.getStartsAt())
                .created("endsAt", saved.getEndsAt())
                .created("dayOfWeek", saved.getDayOfWeek())
                .created("startTime", saved.getStartTime())
                .created("endTime", saved.getEndTime()));
        log.info("Promotion created: id={} stadiumId={} discount={}%",
                saved.getId(), stadium.getId(), saved.getDiscountPercent());
        return promotionMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<PromotionResponse> getPromotions(UUID stadiumId, UUID ownerUserId, Pageable pageable) {
        requireOwnedStadium(stadiumId, ownerUserId);
        return promotionRepository.findByStadiumId(stadiumId, pageable).map(promotionMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> getActivePromotions(UUID stadiumId) {
        return promotionRepository.findByStadiumIdAndIsActiveTrue(stadiumId).stream()
                .map(promotionMapper::toResponse)
                .toList();
    }

    @Transactional
    public void deactivatePromotion(UUID promotionId, UUID ownerUserId) {
        Promotion promotion = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
        if (!promotion.getStadium().getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        boolean wasActive = promotion.isActive();
        promotion.setActive(false);
        promotionRepository.save(promotion);
        if (wasActive) {
            auditService.record(AuditEntry.owner(ownerUserId, AuditAction.PROMOTION_DEACTIVATED)
                    .entity(AuditEntityType.PROMOTION, promotionId, AuditLabels.promotion(promotion))
                    .change("isActive", true, false));
        }
        log.info("Promotion deactivated: id={} ownerId={}", promotionId, ownerUserId);
    }

    /** Best (highest) discount for the stadium at the given instant, or null if none applies. */
    @Transactional(readOnly = true)
    public Integer getDiscountPercent(UUID stadiumId, OffsetDateTime dateTime) {
        return getBestPromotion(stadiumId, dateTime).map(Promotion::getDiscountPercent).orElse(null);
    }

    /** The highest-discount promotion covering the given instant, used by availability. */
    @Transactional(readOnly = true)
    public Optional<Promotion> getBestPromotion(UUID stadiumId, OffsetDateTime dateTime) {
        ZonedDateTime local = dateTime.atZoneSameInstant(TASHKENT);
        int dayOfWeek = local.getDayOfWeek().getValue() - 1;
        return promotionRepository.findMatching(stadiumId, dateTime, dayOfWeek, local.toLocalTime()).stream()
                .max(Comparator.comparingInt(Promotion::getDiscountPercent));
    }

    /** Deactivates active promotions whose window has ended. Idempotent. */
    @Transactional
    public int expireEndedPromotions() {
        List<Promotion> ended = promotionRepository.findByIsActiveTrueAndEndsAtBefore(
                OffsetDateTime.now(ZoneOffset.UTC));
        ended.forEach(p -> p.setActive(false));
        if (!ended.isEmpty()) {
            promotionRepository.saveAll(ended);
            log.info("Expired {} promotion(s)", ended.size());
        }
        return ended.size();
    }

    private Stadium requireOwnedStadium(UUID stadiumId, UUID ownerUserId) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        return stadium;
    }
}
