package uz.minipolya.minipolya_backend.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumResponse;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumAmenities;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.StadiumMapper;
import uz.minipolya.minipolya_backend.repository.FavoriteRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.Districts;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class StadiumService {

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_APPROVED = "approved";

    private final StadiumRepository stadiumRepository;
    private final UserRepository userRepository;
    private final StadiumMapper stadiumMapper;
    private final ReviewRepository reviewRepository;
    private final FavoriteRepository favoriteRepository;
    private final AuditService auditService;
    private final EntityManager entityManager;
    private final CardPricing cardPricing;

    // ---- Owner operations ----

    @Transactional
    public StadiumDetailResponse create(UUID ownerId, CreateStadiumRequest request) {
        requireValidMinBookingAmount(request.getMinBookingAmount());
        request.setDistrict(Districts.require(request.getDistrict()));

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Stadium stadium = stadiumMapper.toEntity(request);
        stadium.setOwner(owner);
        stadium.setStatus(STATUS_PENDING);
        applyCancellationHours(stadium, request.getCancellationHours());

        StadiumAmenities amenities = request.getAmenities() != null
                ? stadiumMapper.toAmenities(request.getAmenities())
                : new StadiumAmenities();
        stadium.setAmenities(amenities);

        replacePhotos(stadium, request.getPhotos());

        Stadium saved = stadiumRepository.save(stadium);
        auditService.record(AuditEntry.owner(ownerId, AuditAction.STADIUM_CREATED)
                .entity(AuditEntityType.STADIUM, saved.getId(), AuditLabels.stadium(saved))
                .diff(Map.of(), auditSnapshot(saved)));
        log.info("Stadium created: id={} ownerId={} status={}", saved.getId(), ownerId, saved.getStatus());
        return ownerPrices(stadiumMapper.toDetailResponse(saved));
    }

    @Transactional
    public StadiumDetailResponse update(UUID ownerId, UUID stadiumId, UpdateStadiumRequest request) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }

        requireValidMinBookingAmount(request.getMinBookingAmount());
        // Absent leaves the district as it is (the update only touches what is sent).
        request.setDistrict(Districts.require(request.getDistrict()));

        Map<String, Object> before = auditSnapshot(stadium);
        stadiumMapper.updateStadiumFromRequest(request, stadium);

        if (request.getAmenities() != null) {
            if (stadium.getAmenities() == null) {
                stadium.setAmenities(stadiumMapper.toAmenities(request.getAmenities()));
            } else {
                stadiumMapper.updateAmenities(request.getAmenities(), stadium.getAmenities());
            }
        }
        if (request.getPhotos() != null) {
            replacePhotos(stadium, request.getPhotos());
        }

        // The stadiums updated_at trigger sets the new timestamp in the database, so
        // flush and refresh to report what was actually stored rather than the value
        // the entity was loaded with.
        Stadium saved = stadiumRepository.saveAndFlush(stadium);
        entityManager.refresh(saved);
        AuditEntry audit = AuditEntry.owner(ownerId, AuditAction.STADIUM_UPDATED)
                .entity(AuditEntityType.STADIUM, saved.getId(), AuditLabels.stadium(saved))
                .diff(before, auditSnapshot(saved));
        if (!audit.getChanges().isEmpty()) {
            auditService.record(audit);
        }
        log.info("Stadium updated: id={} ownerId={}", saved.getId(), ownerId);
        return ownerPrices(stadiumMapper.toDetailResponse(saved));
    }

    @Transactional(readOnly = true)
    public List<StadiumResponse> listOwnerStadiums(UUID ownerId) {
        return stadiumRepository.findByOwnerId(ownerId).stream()
                .map(this::toResponseWithRatings)
                .map(this::ownerPrices)
                .toList();
    }

    // ---- Public operations ----

    @Transactional(readOnly = true)
    public PageResponse<StadiumResponse> searchPublic(String q, String format, Long maxPrice, int page, int size) {
        Pageable pageable = Pagination.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        return PageResponse.from(
                // Players filter by the card price they see; the table holds net prices.
                stadiumRepository.search(blankToNull(q), blankToNull(format),
                        maxPrice == null ? null : cardPricing.maxNetFor(maxPrice), pageable),
                stadium -> publicPrices(toResponseWithRatings(stadium)));
    }

    /** @param userId the viewer, or null for an anonymous request (no favorite flag). */
    @Transactional(readOnly = true)
    public StadiumDetailResponse getPublicDetail(UUID id, UUID userId) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(id, STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        StadiumDetailResponse response = publicPrices(stadiumMapper.toDetailResponse(stadium));
        if (userId != null) {
            response.setFavorited(favoriteRepository.existsByUserIdAndStadiumId(userId, id));
        }
        return response;
    }

    /**
     * What an admin reviews: the same mapping as the public detail, but for a stadium in
     * any status, and with prices as the owner sees them — {@code pricePerHour} and
     * {@code netPrice} net, {@code price} what a player pays by card.
     */
    @Transactional(readOnly = true)
    public StadiumDetailResponse getAdminDetail(UUID id) {
        Stadium stadium = stadiumRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        return ownerPrices(stadiumMapper.toDetailResponse(stadium));
    }

    // ---- Helpers ----

    /** Players see what they pay by card: the hourly price and the deposit both grossed up. */
    private StadiumDetailResponse publicPrices(StadiumDetailResponse r) {
        if (r != null && r.getPricePerHour() != null) {
            CardPricing.Quote q = cardPricing.quote(r.getPricePerHour());
            r.setPricePerHour(q.price());
            r.setPrice(q.price());
            r.setCardFee(q.cardFee());
        }
        if (r != null && r.getMinBookingAmount() != null) {
            r.setMinBookingAmount(cardPricing.playerAmount(r.getMinBookingAmount()));
        }
        return r;
    }

    private StadiumResponse publicPrices(StadiumResponse r) {
        if (r != null && r.getPricePerHour() != null) {
            CardPricing.Quote q = cardPricing.quote(r.getPricePerHour());
            r.setPricePerHour(q.price());
            r.setPrice(q.price());
            r.setCardFee(q.cardFee());
        }
        return r;
    }

    /** The owner keeps seeing and editing their net price; the card price is alongside. */
    private StadiumDetailResponse ownerPrices(StadiumDetailResponse r) {
        if (r != null && r.getPricePerHour() != null) {
            CardPricing.Quote q = cardPricing.quote(r.getPricePerHour());
            r.setPrice(q.price());
            r.setNetPrice(q.netPrice());
            r.setCardFee(q.cardFee());
        }
        return r;
    }

    private StadiumResponse ownerPrices(StadiumResponse r) {
        if (r != null && r.getPricePerHour() != null) {
            CardPricing.Quote q = cardPricing.quote(r.getPricePerHour());
            r.setPrice(q.price());
            r.setNetPrice(q.netPrice());
            r.setCardFee(q.cardFee());
        }
        return r;
    }

    /**
     * The owner-editable fields an audit entry compares, prices and the minimum booking
     * amount in tiyin. Photos as their URLs in order; amenities flattened to one field each.
     */
    static Map<String, Object> auditSnapshot(Stadium stadium) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("name", stadium.getName());
        fields.put("address", stadium.getAddress());
        fields.put("district", stadium.getDistrict());
        fields.put("latitude", stadium.getLatitude());
        fields.put("longitude", stadium.getLongitude());
        fields.put("phone", stadium.getPhone());
        fields.put("description", stadium.getDescription());
        fields.put("format", stadium.getFormat());
        fields.put("surfaceType", stadium.getSurfaceType());
        fields.put("openTime", stadium.getOpenTime());
        fields.put("closeTime", stadium.getCloseTime());
        fields.put("pricePerHour", stadium.getPricePerHour());
        fields.put("minBookingAmount", stadium.getMinBookingAmount());
        fields.put("cancellationHours", stadium.getCancellationHours());
        fields.put("isActive", stadium.isActive());
        fields.put("photos", stadium.getPhotos().stream().map(StadiumPhoto::getUrl).toList());
        StadiumAmenities a = stadium.getAmenities();
        if (a != null) {
            fields.put("amenities.parking", a.isParking());
            fields.put("amenities.lighting", a.isLighting());
            fields.put("amenities.showers", a.isShowers());
            fields.put("amenities.lockerRoom", a.isLockerRoom());
            fields.put("amenities.wifi", a.isWifi());
            fields.put("amenities.cafe", a.isCafe());
            fields.put("amenities.equipmentRental", a.isEquipmentRental());
            fields.put("amenities.tribune", a.isTribune());
        }
        return fields;
    }

    /** Maps a stadium and attaches its computed average rating and review count. */
    private StadiumResponse toResponseWithRatings(Stadium stadium) {
        StadiumResponse response = stadiumMapper.toResponse(stadium);
        response.setAverageRating(reviewRepository.averageRatingByStadiumId(stadium.getId()));
        response.setReviewCount(reviewRepository.countByStadiumId(stadium.getId()));
        return response;
    }

    /**
     * A null minimum is fine — it means the player pays in full. A non-null one
     * must be something ATMOS will actually accept.
     */
    private void requireValidMinBookingAmount(Long minBookingAmount) {
        if (minBookingAmount != null && minBookingAmount < Stadium.MIN_BOOKING_AMOUNT_FLOOR) {
            throw new BadRequestException("minBookingAmount must be at least "
                    + Stadium.MIN_BOOKING_AMOUNT_FLOOR + " tiyin (500 so'm)");
        }
    }

    /** Leaves the stadium's own default in place when the owner sends nothing. */
    private void applyCancellationHours(Stadium stadium, Integer cancellationHours) {
        if (cancellationHours != null) {
            stadium.setCancellationHours(cancellationHours);
        }
    }

    private void replacePhotos(Stadium stadium, List<String> urls) {
        stadium.getPhotos().clear();
        if (urls == null) {
            return;
        }
        int position = 0;
        for (String url : urls) {
            stadium.addPhoto(StadiumPhoto.builder().url(url).position(position++).build());
        }
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
