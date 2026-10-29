package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.AdminStadiumDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminStadiumResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumDetailResponse;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.AdminStadiumMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.StadiumSpecifications;
import uz.minipolya.minipolya_backend.util.Districts;
import uz.minipolya.minipolya_backend.util.Pagination;
import uz.minipolya.minipolya_backend.util.Revenue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Stadiums for the admin panel, in every status. A list page costs a fixed handful of
 * queries — the stadiums with owner and amenities, the count, then ratings, booking
 * counts, revenue and photos for the whole page at once.
 */
@Service
@RequiredArgsConstructor
public class AdminStadiumService {

    private static final List<String> COUNTED_STATUSES = List.of("confirmed", "completed");
    private static final Set<String> FORMATS = Set.of("5x5", "7x7", "9x9", "11x11");

    private final StadiumRepository stadiumRepository;
    private final ReviewRepository reviewRepository;
    private final BookingRepository bookingRepository;
    private final RevenueService revenueService;
    private final StadiumService stadiumService;
    private final PricingService pricingService;
    private final AdminStadiumMapper adminStadiumMapper;
    private final CardPricing cardPricing;

    @Transactional(readOnly = true)
    public Page<AdminStadiumResponse> list(String status, String search, String district, String format,
                                           UUID ownerId, int page, int size) {
        String validDistrict = Districts.require(district);
        String validFormat = requireFormat(format);
        Pageable pageable = Pagination.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));
        Page<Stadium> stadiums = stadiumRepository.findAll(
                StadiumSpecifications.adminFilter(status, search, validDistrict, validFormat, ownerId), pageable);
        List<AdminStadiumResponse> rows = toResponses(stadiums.getContent());
        Map<UUID, AdminStadiumResponse> byId = rows.stream()
                .collect(Collectors.toMap(AdminStadiumResponse::getId, r -> r));
        return stadiums.map(stadium -> byId.get(stadium.getId()));
    }

    /** One stadium as the list shows it — what a status change answers with. */
    @Transactional(readOnly = true)
    public AdminStadiumResponse toResponse(Stadium stadium) {
        return toResponses(List.of(stadium)).get(0);
    }

    @Transactional(readOnly = true)
    public AdminStadiumDetailResponse get(UUID stadiumId) {
        StadiumDetailResponse detail = stadiumService.getAdminDetail(stadiumId);
        Stadium stadium = stadiumRepository.findById(stadiumId)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));
        AdminStadiumResponse row = toResponse(stadium);
        return AdminStadiumDetailResponse.builder()
                .stadium(detail)
                .photoUrl(row.getPhotoUrl())
                .cancellationHours(stadium.getCancellationHours())
                .averageRating(row.getAverageRating())
                .reviewCount(row.getReviewCount())
                .totalBookings(row.getTotalBookings())
                .totalRevenue(row.getTotalRevenue())
                .pricingRules(pricingService.listForAdmin(stadiumId))
                .build();
    }

    private List<AdminStadiumResponse> toResponses(List<Stadium> stadiums) {
        if (stadiums.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = stadiums.stream().map(Stadium::getId).toList();
        Map<UUID, Object[]> ratings = reviewRepository.ratingsByStadiumIds(ids).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> row));
        Map<UUID, Long> bookings = bookingRepository.countByStadiumIdsAndStatusIn(ids, COUNTED_STATUSES).stream()
                .collect(Collectors.toMap(row -> (UUID) row[0], row -> (Long) row[1]));
        Map<UUID, Revenue> revenue = revenueService.totalByStadium(ids);
        Map<UUID, String> firstPhoto = stadiumRepository.findPhotosByStadiumIds(ids).stream()
                .collect(Collectors.toMap(p -> p.getStadium().getId(), StadiumPhoto::getUrl, (first, later) -> first));
        CardPricing pricing = cardPricing.snapshot();

        return stadiums.stream().map(stadium -> {
            AdminStadiumResponse response = adminStadiumMapper.toResponse(stadium);
            Object[] rating = ratings.get(stadium.getId());
            response.setAverageRating(rating == null || rating[1] == null ? 0.0 : ((Number) rating[1]).doubleValue());
            response.setReviewCount(rating == null ? 0 : ((Number) rating[2]).intValue());
            response.setTotalBookings(bookings.getOrDefault(stadium.getId(), 0L).intValue());
            response.setTotalRevenue(revenue.getOrDefault(stadium.getId(), Revenue.ZERO).appRevenue());
            response.setPhotoUrl(firstPhoto.get(stadium.getId()));
            if (stadium.getPricePerHour() != null) {
                response.setPrice(pricing.playerAmount(stadium.getPricePerHour()));
            }
            return response;
        }).toList();
    }

    private static String requireFormat(String format) {
        if (format == null || format.isBlank()) {
            return null;
        }
        if (!FORMATS.contains(format)) {
            throw new BadRequestException("Invalid format");
        }
        return format;
    }
}
