package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.request.CreatePricingRuleRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdatePricingRuleRequest;
import uz.minipolya.minipolya_backend.dto.response.PricingRuleResponse;
import uz.minipolya.minipolya_backend.entity.PricingRule;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.PricingRuleMapper;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;

import java.time.LocalTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class PricingService {

    private static final String STATUS_APPROVED = "approved";

    private final PricingRuleRepository pricingRuleRepository;
    private final StadiumRepository stadiumRepository;
    private final PricingRuleMapper pricingRuleMapper;

    // ---- Owner operations ----

    @Transactional(readOnly = true)
    public List<PricingRuleResponse> listForOwner(Long ownerId, Long stadiumId) {
        requireOwnedStadium(ownerId, stadiumId);
        return mapActive(stadiumId);
    }

    @Transactional
    public PricingRuleResponse create(Long ownerId, Long stadiumId, CreatePricingRuleRequest request) {
        Stadium stadium = requireOwnedStadium(ownerId, stadiumId);

        validateTimeOrder(request.getStartTime(), request.getEndTime());
        validateNoOverlap(stadiumId, request.getDayOfWeek(), request.getStartTime(), request.getEndTime(), null);

        PricingRule rule = pricingRuleMapper.toEntity(request);
        rule.setStadium(stadium);
        PricingRule saved = pricingRuleRepository.save(rule);
        log.info("Pricing rule created: id={} stadiumId={} day={} {}-{}",
                saved.getId(), stadiumId, saved.getDayOfWeek(), saved.getStartTime(), saved.getEndTime());
        return pricingRuleMapper.toResponse(saved);
    }

    @Transactional
    public PricingRuleResponse update(Long ownerId, Long stadiumId, Long ruleId, UpdatePricingRuleRequest request) {
        requireOwnedStadium(ownerId, stadiumId);
        PricingRule rule = requireRuleInStadium(ruleId, stadiumId);

        pricingRuleMapper.updateFromRequest(request, rule);

        validateTimeOrder(rule.getStartTime(), rule.getEndTime());
        validateNoOverlap(stadiumId, rule.getDayOfWeek(), rule.getStartTime(), rule.getEndTime(), ruleId);

        PricingRule saved = pricingRuleRepository.save(rule);
        log.info("Pricing rule updated: id={} stadiumId={}", saved.getId(), stadiumId);
        return pricingRuleMapper.toResponse(saved);
    }

    @Transactional
    public void softDelete(Long ownerId, Long stadiumId, Long ruleId) {
        requireOwnedStadium(ownerId, stadiumId);
        PricingRule rule = requireRuleInStadium(ruleId, stadiumId);
        rule.setActive(false);
        pricingRuleRepository.save(rule);
        log.info("Pricing rule soft-deleted: id={} stadiumId={}", ruleId, stadiumId);
    }

    // ---- Public operations ----

    @Transactional(readOnly = true)
    public List<PricingRuleResponse> listPublic(Long stadiumId) {
        stadiumRepository.findByIdAndStatus(stadiumId, STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        return mapActive(stadiumId);
    }

    // ---- Helpers ----

    private List<PricingRuleResponse> mapActive(Long stadiumId) {
        return pricingRuleRepository.findByStadiumIdAndIsActiveTrue(stadiumId).stream()
                .map(pricingRuleMapper::toResponse)
                .toList();
    }

    private Stadium requireOwnedStadium(Long ownerId, Long stadiumId) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        return stadium;
    }

    private PricingRule requireRuleInStadium(Long ruleId, Long stadiumId) {
        PricingRule rule = pricingRuleRepository.findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException("Pricing rule not found"));
        if (!rule.getStadium().getId().equals(stadiumId)) {
            throw new ResourceNotFoundException("Pricing rule not found");
        }
        return rule;
    }

    private void validateTimeOrder(LocalTime start, LocalTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new BadRequestException("startTime must be before endTime");
        }
    }

    private void validateNoOverlap(Long stadiumId, Integer dayOfWeek, LocalTime start, LocalTime end, Long excludeRuleId) {
        boolean overlaps = pricingRuleRepository.findByStadiumIdAndDayOfWeek(stadiumId, dayOfWeek).stream()
                .filter(PricingRule::isActive)
                .filter(r -> excludeRuleId == null || !r.getId().equals(excludeRuleId))
                .anyMatch(r -> start.isBefore(r.getEndTime()) && r.getStartTime().isBefore(end));
        if (overlaps) {
            throw new BadRequestException("Pricing rule overlaps an existing rule for that day");
        }
    }
}
