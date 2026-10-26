package uz.minipolya.minipolya_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerDashboardStatsResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerRevenueResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerScheduleResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.ReviewResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumResponse;
import uz.minipolya.minipolya_backend.service.BookingService;
import uz.minipolya.minipolya_backend.service.OwnerDashboardService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class OwnerDashboardController {

    private final OwnerDashboardService dashboardService;
    private final BookingService bookingService;

    @GetMapping("/api/owner/dashboard/stadiums")
    public ApiResponse<List<StadiumResponse>> myStadiums(@AuthenticationPrincipal UUID ownerId) {
        return ApiResponse.ok(dashboardService.getMyStadiums(ownerId));
    }

    @GetMapping("/api/owner/dashboard/stadiums/{stadiumId}/stats")
    public ApiResponse<OwnerDashboardStatsResponse> stats(@AuthenticationPrincipal UUID ownerId,
                                                          @PathVariable UUID stadiumId) {
        return ApiResponse.ok(dashboardService.getStats(stadiumId, ownerId));
    }

    @GetMapping("/api/owner/dashboard/stadiums/{stadiumId}/revenue")
    public ApiResponse<OwnerRevenueResponse> revenue(@AuthenticationPrincipal UUID ownerId,
                                                     @PathVariable UUID stadiumId) {
        return ApiResponse.ok(dashboardService.getRevenue(stadiumId, ownerId));
    }

    @GetMapping("/api/owner/dashboard/stadiums/{stadiumId}/schedule")
    public ApiResponse<List<OwnerScheduleResponse>> schedule(
            @AuthenticationPrincipal UUID ownerId,
            @PathVariable UUID stadiumId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(dashboardService.getSchedule(stadiumId, from, to, ownerId));
    }

    @GetMapping("/api/owner/dashboard/stadiums/{stadiumId}/reviews")
    public ApiResponse<PageResponse<ReviewResponse>> reviews(@AuthenticationPrincipal UUID ownerId,
                                                             @PathVariable UUID stadiumId,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(
                dashboardService.getStadiumReviews(stadiumId, ownerId, pageable), Function.identity()));
    }

    @GetMapping("/api/owner/dashboard/stadiums/{stadiumId}/bookings")
    public ApiResponse<PageResponse<BookingResponse>> bookings(
            @AuthenticationPrincipal UUID ownerId,
            @PathVariable UUID stadiumId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(bookingService.getStadiumBookings(ownerId, stadiumId, from, to, page, size));
    }
}
