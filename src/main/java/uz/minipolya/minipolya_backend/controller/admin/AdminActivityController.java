package uz.minipolya.minipolya_backend.controller.admin;

import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.AdminBookingResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminGameDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminGameResponse;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.AdminBookingService;
import uz.minipolya.minipolya_backend.service.AdminGameService;

import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;

/**
 * Every booking and every game, for the admin panel's Bronlar page and a user's page.
 * Read only. {@code from} / {@code to} are Tashkent days on {@code startsAt}, both
 * inclusive. The status history of either comes from the audit log.
 */
@RestController
@RequiredArgsConstructor
public class AdminActivityController {

    private final AdminBookingService adminBookingService;
    private final AdminGameService adminGameService;

    @GetMapping("/api/admin/bookings")
    public ApiResponse<PageResponse<AdminBookingResponse>> bookings(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID stadiumId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResponse.from(
                adminBookingService.list(search, stadiumId, status, from, to, userId, page, size),
                Function.identity()));
    }

    @GetMapping("/api/admin/bookings/{id}")
    public ApiResponse<AdminBookingResponse> booking(@PathVariable UUID id) {
        return ApiResponse.ok(adminBookingService.get(id));
    }

    @GetMapping("/api/admin/games")
    public ApiResponse<PageResponse<AdminGameResponse>> games(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) UUID stadiumId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID userId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(PageResponse.from(
                adminGameService.list(search, stadiumId, status, from, to, userId, page, size),
                Function.identity()));
    }

    @GetMapping("/api/admin/games/{id}")
    public ApiResponse<AdminGameDetailResponse> game(@PathVariable UUID id) {
        return ApiResponse.ok(adminGameService.get(id));
    }
}
