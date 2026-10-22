package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingSeriesRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.BookingSeriesResponse;
import uz.minipolya.minipolya_backend.service.BookingSeriesService;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class BookingSeriesController {

    private final BookingSeriesService bookingSeriesService;

    @PostMapping("/api/booking-series")
    public ApiResponse<BookingSeriesResponse> create(@AuthenticationPrincipal UUID userId,
                                                     @Valid @RequestBody CreateBookingSeriesRequest request) {
        return ApiResponse.ok(bookingSeriesService.createSeries(request, userId), "Recurring booking created");
    }

    @GetMapping("/api/booking-series/my")
    public ApiResponse<List<BookingSeriesResponse>> mySeries(@AuthenticationPrincipal UUID userId) {
        return ApiResponse.ok(bookingSeriesService.getMySeries(userId));
    }

    @DeleteMapping("/api/booking-series/{id}")
    public ApiResponse<Void> cancel(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        bookingSeriesService.cancelSeries(id, userId);
        return ApiResponse.ok(null, "Recurring booking cancelled");
    }
}
