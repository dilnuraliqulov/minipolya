package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateManualBookingRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.BookingService;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /**
     * Creates the booking and charges the chosen card. The booking survives a
     * declined card — it stays pending until the hold expires — so the outcome
     * is reported in the message and in {@code paymentStatus} rather than as an
     * error status.
     */
    @PostMapping("/api/bookings")
    public ApiResponse<BookingResponse> create(@AuthenticationPrincipal UUID userId,
                                               @Valid @RequestBody CreateBookingRequest request) {
        BookingResponse booking = bookingService.createBooking(userId, request);
        return ApiResponse.ok(booking, switch (booking.getPaymentStatus()) {
            case PAID -> "Booking confirmed and paid in full";
            case PARTIALLY_PAID -> "Booking confirmed; the remainder is still due";
            case UNPAID -> "Payment failed — the booking is held until it expires; try paying again";
        });
    }

    @GetMapping("/api/bookings")
    public ApiResponse<PageResponse<BookingResponse>> myBookings(@AuthenticationPrincipal UUID userId,
                                                                 @RequestParam(defaultValue = "0") int page,
                                                                 @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(bookingService.getMyBookings(userId, page, size));
    }

    @GetMapping("/api/bookings/{id}")
    public ApiResponse<BookingResponse> detail(@AuthenticationPrincipal UUID userId,
                                               @PathVariable UUID id) {
        return ApiResponse.ok(bookingService.getBookingForUser(userId, id));
    }

    @PostMapping("/api/bookings/{id}/cancel")
    public ApiResponse<BookingResponse> cancel(@AuthenticationPrincipal UUID userId,
                                               @PathVariable UUID id,
                                               @RequestParam(required = false) String reason) {
        return ApiResponse.ok(bookingService.cancelByUser(userId, id, reason), "Booking cancelled");
    }

    @PostMapping("/api/owner/stadiums/{stadiumId}/bookings")
    public ApiResponse<BookingResponse> createManual(@AuthenticationPrincipal UUID ownerId,
                                                     @PathVariable UUID stadiumId,
                                                     @Valid @RequestBody CreateManualBookingRequest request) {
        request.setStadiumId(stadiumId); // path is authoritative
        return ApiResponse.ok(bookingService.createManualBooking(request, ownerId), "Manual booking created");
    }

    @GetMapping("/api/owner/stadiums/{stadiumId}/bookings")
    public ApiResponse<PageResponse<BookingResponse>> stadiumBookings(
            @AuthenticationPrincipal UUID ownerId,
            @PathVariable UUID stadiumId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(bookingService.getStadiumBookings(ownerId, stadiumId, from, to, page, size));
    }

    @DeleteMapping("/api/owner/bookings/{bookingId}")
    public ApiResponse<BookingResponse> cancelByOwner(@AuthenticationPrincipal UUID ownerId,
                                                      @PathVariable UUID bookingId,
                                                      @RequestParam(required = false) String reason) {
        return ApiResponse.ok(bookingService.cancelByOwner(ownerId, bookingId, reason), "Booking cancelled");
    }
}
