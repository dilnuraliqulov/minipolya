package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.BookingService;

@RestController
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping("/api/bookings")
    public ApiResponse<BookingResponse> create(@AuthenticationPrincipal Long userId,
                                               @Valid @RequestBody CreateBookingRequest request) {
        return ApiResponse.ok(bookingService.createBooking(userId, request), "Booking held for payment");
    }

    @GetMapping("/api/bookings")
    public ApiResponse<PageResponse<BookingResponse>> myBookings(@AuthenticationPrincipal Long userId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(bookingService.getMyBookings(userId, page, size));
    }

    @GetMapping("/api/bookings/{id}")
    public ApiResponse<BookingResponse> detail(@AuthenticationPrincipal Long userId,
                                               @PathVariable Long id) {
        return ApiResponse.ok(bookingService.getBookingForUser(userId, id));
    }

    @PostMapping("/api/bookings/{id}/cancel")
    public ApiResponse<BookingResponse> cancel(@AuthenticationPrincipal Long userId,
                                               @PathVariable Long id,
                                               @RequestParam(required = false) String reason) {
        return ApiResponse.ok(bookingService.cancelByUser(userId, id, reason), "Booking cancelled");
    }

    @GetMapping("/api/owner/stadiums/{stadiumId}/bookings")
    public ApiResponse<PageResponse<BookingResponse>> stadiumBookings(@AuthenticationPrincipal Long ownerId,
                                                                      @PathVariable Long stadiumId,
                                                                      @RequestParam(defaultValue = "0") int page,
                                                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(bookingService.getStadiumBookings(ownerId, stadiumId, page, size));
    }
}
