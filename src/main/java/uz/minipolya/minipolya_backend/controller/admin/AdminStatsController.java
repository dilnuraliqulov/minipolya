package uz.minipolya.minipolya_backend.controller.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.AdminPlatformStatsResponse;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.DailyBookingCountResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerRevenueResponse;
import uz.minipolya.minipolya_backend.service.AdminService;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class AdminStatsController {

    private final AdminService adminService;

    @GetMapping("/api/admin/stats")
    public ApiResponse<AdminPlatformStatsResponse> stats() {
        return ApiResponse.ok(adminService.getPlatformStats());
    }

    @GetMapping("/api/admin/stats/revenue")
    public ApiResponse<OwnerRevenueResponse> revenue() {
        return ApiResponse.ok(adminService.getPlatformRevenue());
    }

    /** Bookings starting on each of the last 30 Tashkent days, every day present. */
    @GetMapping("/api/admin/stats/bookings-daily")
    public ApiResponse<List<DailyBookingCountResponse>> bookingsDaily() {
        return ApiResponse.ok(adminService.getDailyBookings());
    }
}
