package uz.minipolya.minipolya_backend.service;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.List;
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

    // ---- Owner operations ----

    @Transactional
    public StadiumDetailResponse create(UUID ownerId, CreateStadiumRequest request) {
        requireValidMinBookingAmount(request.getMinBookingAmount());

        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Stadium stadium = stadiumMapper.toEntity(request);
        stadium.setOwner(owner);
        stadium.setStatus(STATUS_PENDING);
        applyRefundOverrides(stadium, request.getFullRefundHours(),
                request.getPartialRefundHours(), request.getPartialRefundPercent());

        StadiumAmenities amenities = request.getAmenities() != null
                ? stadiumMapper.toAmenities(request.getAmenities())
                : new StadiumAmenities();
        stadium.setAmenities(amenities);

        replacePhotos(stadium, request.getPhotos());

        Stadium saved = stadiumRepository.save(stadium);
        auditService.log(ownerId, "STADIUM_CREATED", "stadium", saved.getId());
        log.info("Stadium created: id={} ownerId={} status={}", saved.getId(), ownerId, saved.getStatus());
        return stadiumMapper.toDetailResponse(saved);
    }

    @Transactional
    public StadiumDetailResponse update(UUID ownerId, UUID stadiumId, UpdateStadiumRequest request) {
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        if (!stadium.getOwner().getId().equals(ownerId)) {
            throw new ForbiddenException("You do not own this stadium");
        }

        requireValidMinBookingAmount(request.getMinBookingAmount());

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
        auditService.log(ownerId, "STADIUM_UPDATED", "stadium", saved.getId());
        log.info("Stadium updated: id={} ownerId={}", saved.getId(), ownerId);
        return stadiumMapper.toDetailResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<StadiumResponse> listOwnerStadiums(UUID ownerId) {
        return stadiumRepository.findByOwnerId(ownerId).stream()
                .map(this::toResponseWithRatings)
                .toList();
    }

    // ---- Public operations ----

    @Transactional(readOnly = true)
    public PageResponse<StadiumResponse> searchPublic(String q, String format, Long maxPrice, int page, int size) {
        Pageable pageable = Pagination.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        return PageResponse.from(
                stadiumRepository.search(blankToNull(q), blankToNull(format), maxPrice, pageable),
                this::toResponseWithRatings);
    }

    /** @param userId the viewer, or null for an anonymous request (no favorite flag). */
    @Transactional(readOnly = true)
    public StadiumDetailResponse getPublicDetail(UUID id, UUID userId) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(id, STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        StadiumDetailResponse response = stadiumMapper.toDetailResponse(stadium);
        if (userId != null) {
            response.setFavorited(favoriteRepository.existsByUserIdAndStadiumId(userId, id));
        }
        return response;
    }

    // ---- Helpers ----

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

    private void applyRefundOverrides(Stadium stadium, Integer fullHours, Integer partialHours, Integer partialPercent) {
        if (fullHours != null) {
            stadium.setFullRefundHours(fullHours);
        }
        if (partialHours != null) {
            stadium.setPartialRefundHours(partialHours);
        }
        if (partialPercent != null) {
            stadium.setPartialRefundPercent(partialPercent);
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
