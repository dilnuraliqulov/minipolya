package uz.minipolya.minipolya_backend.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
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
import uz.minipolya.minipolya_backend.repository.StadiumSpecifications;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.Districts;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
        stadium.setLandmark(landmark(request.getLandmark()));
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
        return toOwnerDetail(saved);
    }

    @Transactional
    public StadiumDetailResponse update(UUID ownerId, UUID stadiumId, UpdateStadiumRequest request) {
        Stadium stadium = requireOwnedStadium(ownerId, stadiumId);

        boolean clearMinimum = Boolean.TRUE.equals(request.getClearMinBookingAmount());
        if (clearMinimum && request.getMinBookingAmount() != null) {
            throw new BadRequestException("Send either minBookingAmount or clearMinBookingAmount");
        }
        requireValidMinBookingAmount(request.getMinBookingAmount());
        // Absent leaves the district as it is (the update only touches what is sent).
        request.setDistrict(Districts.require(request.getDistrict()));

        Map<String, Object> before = auditSnapshot(stadium);
        stadiumMapper.updateStadiumFromRequest(request, stadium);
        if (request.getLandmark() != null) {
            stadium.setLandmark(landmark(request.getLandmark())); // blank clears it
        }
        if (clearMinimum) {
            stadium.setMinBookingAmount(null); // full payment only; audited like any field
        }

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
        return toOwnerDetail(saved);
    }

    /**
     * R35: the owner's own stadium in any status, exactly as {@link #update} answers it —
     * the owner's prices with the player prices alongside, status and status reason.
     */
    @Transactional(readOnly = true)
    public StadiumDetailResponse getOwnerDetail(UUID ownerId, UUID stadiumId) {
        return toOwnerDetail(requireOwnedStadium(ownerId, stadiumId));
    }

    /**
     * The stadium, if the caller owns it — any status. 404 "Stadium not found", 403 "You do
     * not own this stadium".
     */
    @Transactional(readOnly = true)
    public Stadium requireOwnedStadium(UUID ownerId, UUID stadiumId) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }
        return stadium;
    }

    /** The owner's view of one stadium: what create, update and the owner detail return. */
    StadiumDetailResponse toOwnerDetail(Stadium stadium) {
        return ownerPrices(stadiumMapper.toDetailResponse(stadium));
    }

    @Transactional(readOnly = true)
    public List<StadiumResponse> listOwnerStadiums(UUID ownerId) {
        CardPricing pricing = cardPricing.snapshot();
        List<StadiumResponse> rows = toListResponses(stadiumRepository.findByOwnerId(ownerId), null);
        rows.forEach(r -> {
            if (r.getPricePerHour() != null) {
                r.setPlayerPrice(pricing.playerAmount(r.getPricePerHour()));
            }
        });
        return rows;
    }

    // ---- Public operations ----

    /**
     * The players' stadium search. Every filter is optional; {@code district} must be one of
     * the twelve ({@link Districts}) and {@code maxPrice} is a player price. A page costs a
     * fixed handful of queries: the stadiums with owner and amenities, the count, then
     * ratings, cover photos and the caller's favorites for the whole page at once.
     *
     * @param viewerId the caller, or null when anonymous (no favorite flag)
     */
    @Transactional(readOnly = true)
    public PageResponse<StadiumResponse> searchPublic(String q, String format, Long maxPrice, String district,
                                                      UUID viewerId, int page, int size) {
        String validDistrict = Districts.require(district);
        Pageable pageable = Pagination.of(page, size,
                Sort.by(Sort.Direction.ASC, "createdAt").and(Sort.by(Sort.Direction.ASC, "id")));
        CardPricing pricing = cardPricing.snapshot();
        // Players filter by the price they see; the table holds the owner's prices.
        Page<Stadium> stadiums = stadiumRepository.findAll(StadiumSpecifications.publicFilter(
                blankToNull(q), blankToNull(format),
                maxPrice == null ? null : pricing.maxNetFor(maxPrice), validDistrict), pageable);
        Map<UUID, StadiumResponse> byId = toPublicResponses(stadiums.getContent(), viewerId, pricing).stream()
                .collect(Collectors.toMap(StadiumResponse::getId, r -> r));
        return PageResponse.from(stadiums, stadium -> byId.get(stadium.getId()));
    }

    /**
     * Stadiums as the player lists show them (the search and the favorites), in the given
     * order, with player prices, rating, cover photo and, for a signed-in viewer, the
     * favorite flag, loaded in one grouped query each.
     */
    @Transactional(readOnly = true)
    public List<StadiumResponse> toPublicResponses(List<Stadium> stadiums, UUID viewerId) {
        return toPublicResponses(stadiums, viewerId, cardPricing.snapshot());
    }

    private List<StadiumResponse> toPublicResponses(List<Stadium> stadiums, UUID viewerId, CardPricing pricing) {
        List<StadiumResponse> rows = toListResponses(stadiums, viewerId);
        rows.forEach(r -> {
            if (r.getPricePerHour() != null) {
                r.setPricePerHour(pricing.playerAmount(r.getPricePerHour()));
            }
        });
        return rows;
    }

    /** @param userId the viewer, or null for an anonymous request (no favorite flag). */
    @Transactional(readOnly = true)
    public StadiumDetailResponse getPublicDetail(UUID id, UUID userId) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(id, STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        StadiumDetailResponse response = publicPrices(stadiumMapper.toDetailResponse(stadium));
        response.setAverageRating(reviewRepository.averageRatingByStadiumId(id));
        response.setReviewCount(reviewRepository.countByStadiumId(id));
        if (userId != null) {
            response.setFavorited(favoriteRepository.existsByUserIdAndStadiumId(userId, id));
        }
        return response;
    }

    /**
     * What an admin reviews: the same mapping as the public detail, but for a stadium in
     * any status, and with prices as the owner sees them — {@code pricePerHour} the owner's
     * own price, {@code playerPrice} what players pay — plus what the owner receives of an
     * hour paid by card and what ATMOS takes of it.
     */
    @Transactional(readOnly = true)
    public StadiumDetailResponse getAdminDetail(UUID id) {
        Stadium stadium = stadiumRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        StadiumDetailResponse r = ownerPrices(stadiumMapper.toDetailResponse(stadium));
        if (r.getPlayerPrice() != null) {
            r.setOwnerReceives(cardPricing.ownerReceives(r.getPlayerPrice()));
            r.setAtmosFee(r.getPlayerPrice() - r.getOwnerReceives());
        }
        return r;
    }

    // ---- Helpers ----

    /** Players see one price: the hour and the deposit as they pay them. */
    private StadiumDetailResponse publicPrices(StadiumDetailResponse r) {
        if (r != null && r.getPricePerHour() != null) {
            r.setPricePerHour(cardPricing.playerAmount(r.getPricePerHour()));
        }
        if (r != null && r.getMinBookingAmount() != null) {
            r.setMinBookingAmount(cardPricing.playerAmount(r.getMinBookingAmount()));
        }
        return r;
    }

    /** The owner keeps seeing and editing their own price; the player price is alongside. */
    private StadiumDetailResponse ownerPrices(StadiumDetailResponse r) {
        if (r != null && r.getPricePerHour() != null) {
            r.setPlayerPrice(cardPricing.playerAmount(r.getPricePerHour()));
        }
        if (r != null && r.getMinBookingAmount() != null) {
            r.setPlayerMinBookingAmount(cardPricing.playerAmount(r.getMinBookingAmount()));
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
        fields.put("landmark", stadium.getLandmark());
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
            fields.put("amenities.toilet", a.isToilet());
            fields.put("amenities.covered", a.isCovered());
            fields.put("amenities.bibs", a.isBibs());
            fields.put("amenities.drinkingWater", a.isDrinkingWater());
        }
        return fields;
    }

    /**
     * Maps stadiums for a list, in order, with their rating, cover photo and (for a viewer)
     * favorite flag: one grouped query each for the whole list, never one per row. Prices
     * are left as the owner's; the caller converts them for its audience.
     */
    private List<StadiumResponse> toListResponses(List<Stadium> stadiums, UUID viewerId) {
        if (stadiums.isEmpty()) {
            return new ArrayList<>();
        }
        List<UUID> ids = stadiums.stream().map(Stadium::getId).toList();
        Map<UUID, Object[]> ratings = reviewRepository.ratingsByStadiumIds(ids).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> row));
        Map<UUID, String> coverPhotos = stadiumRepository.findPhotosByStadiumIds(ids).stream()
                .collect(Collectors.toMap(p -> p.getStadium().getId(), StadiumPhoto::getUrl, (first, later) -> first));
        Set<UUID> favorited = viewerId == null ? Set.of()
                : new HashSet<>(favoriteRepository.findFavoritedStadiumIds(viewerId, ids));

        return stadiums.stream().map(stadium -> {
            StadiumResponse response = stadiumMapper.toResponse(stadium);
            Object[] rating = ratings.get(stadium.getId());
            response.setAverageRating(rating == null || rating[1] == null ? null : ((Number) rating[1]).doubleValue());
            response.setReviewCount(rating == null ? 0 : ((Number) rating[2]).intValue());
            response.setCoverPhotoUrl(coverPhotos.get(stadium.getId()));
            if (viewerId != null) {
                response.setFavorited(favorited.contains(stadium.getId()));
            }
            return response;
        }).collect(Collectors.toCollection(ArrayList::new));
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

    /** Trimmed; blank is none. At most 150 characters. */
    private static String landmark(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > 150) {
            throw new BadRequestException("landmark must be at most 150 characters");
        }
        return trimmed;
    }

    private String blankToNull(String value) {
        return (value == null || value.isBlank()) ? null : value.trim();
    }
}
