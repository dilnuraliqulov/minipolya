package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumResponse;
import uz.minipolya.minipolya_backend.service.StadiumService;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class StadiumController {

    private final StadiumService stadiumService;

    // ---- Public (players) ----

    @GetMapping("/api/stadiums")
    public ApiResponse<PageResponse<StadiumResponse>> list(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) Integer maxPrice,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(stadiumService.searchPublic(q, format, maxPrice, page, size));
    }

    @GetMapping("/api/stadiums/{id}")
    public ApiResponse<StadiumDetailResponse> detail(@PathVariable Long id) {
        return ApiResponse.ok(stadiumService.getPublicDetail(id));
    }

    // ---- Owner ----

    @PostMapping("/api/owner/stadiums")
    public ApiResponse<StadiumDetailResponse> create(@AuthenticationPrincipal Long ownerId,
                                                     @Valid @RequestBody CreateStadiumRequest request) {
        return ApiResponse.ok(stadiumService.create(ownerId, request), "Stadium submitted for review");
    }

    @PutMapping("/api/owner/stadiums/{id}")
    public ApiResponse<StadiumDetailResponse> update(@AuthenticationPrincipal Long ownerId,
                                                     @PathVariable Long id,
                                                     @Valid @RequestBody UpdateStadiumRequest request) {
        return ApiResponse.ok(stadiumService.update(ownerId, id, request));
    }

    @GetMapping("/api/owner/stadiums")
    public ApiResponse<List<StadiumResponse>> myStadiums(@AuthenticationPrincipal Long ownerId) {
        return ApiResponse.ok(stadiumService.listOwnerStadiums(ownerId));
    }
}
