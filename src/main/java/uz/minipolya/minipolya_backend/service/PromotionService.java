package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.List;

/** Owner-managed stadium discounts, applied in availability/pricing. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromotionService {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private final PromotionRepository promotionRepository;
    private final StadiumRepository stadiumRepository;
    private final PromotionMapper promotionMapper;

    @Transactional
    public PromotionResponse createPromotion(CreatePromotionRequest request, Long ownerUserId) {
        Stadium stadium = requireOwnedStadium(request.getStadiumId(), ownerUserId);
        if (!request.getEndsAt().isAfter(request.getStartsAt())) {
            throw new BadRequestException("endsAt must be after startsAt");
        }
        Promotion promotion = promotionMapper.toEntity(request);
        promotion.setStadium(stadium);
        Promotion saved = promotionRepository.save(promotion);
        log.info("Promotion created: id={} stadiumId={} discount={}%",
                saved.getId(), stadium.getId(), saved.getDiscountPercent());
        return promotionMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public Page<PromotionResponse> getPromotions(Long stadiumId, Long ownerUserId, Pageable pageable) {
        requireOwnedStadium(stadiumId, ownerUserId);
        return promotionRepository.findByStadiumId(stadiumId, pageable).map(promotionMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public List<PromotionResponse> getActivePromotions(Long stadiumId) {
        return promotionRepository.findByStadiumIdAndIsActiveTrue(stadiumId).stream()
                .map(promotionMapper::toResponse)
                .toList();
    }

    @Transactional
    public void deactivatePromotion(Long promotionId, Long ownerUserId) {
        Promotion promotion = promotionRepository.findById(promotionId)
                .orElseThrow(() -> new ResourceNotFoundException("Promotion not found"));
        if (!promotion.getStadium().getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        promotion.setActive(false);
        promotionRepository.save(promotion);
        log.info("Promotion deactivated: id={} ownerId={}", promotionId, ownerUserId);
    }

    /** Best (highest) discount for the stadium at the given instant, or null if none applies. */
    @Transactional(readOnly = true)
    public Integer getDiscountPercent(Long stadiumId, OffsetDateTime dateTime) {
        ZonedDateTime local = dateTime.atZoneSameInstant(TASHKENT);
        int dayOfWeek = local.getDayOfWeek().getValue() - 1;
        return promotionRepository.findMatching(stadiumId, dateTime, dayOfWeek, local.toLocalTime()).stream()
                .map(Promotion::getDiscountPercent)
                .max(Integer::compareTo)
                .orElse(null);
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

    private Stadium requireOwnedStadium(Long stadiumId, Long ownerUserId) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerUserId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        return stadium;
    }
}
