package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.CreatePromotionRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.PromotionResponse;
import uz.minipolya.minipolya_backend.service.PromotionService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class PromotionController {

    private final PromotionService promotionService;

    // ---- Owner ----

    @PostMapping("/api/owner/stadiums/{stadiumId}/promotions")
    public ApiResponse<PromotionResponse> create(@AuthenticationPrincipal UUID ownerId,
                                                 @PathVariable UUID stadiumId,
                                                 @Valid @RequestBody CreatePromotionRequest request) {
        request.setStadiumId(stadiumId); // path is authoritative
        return ApiResponse.ok(promotionService.createPromotion(request, ownerId), "Promotion created");
    }

    @GetMapping("/api/owner/stadiums/{stadiumId}/promotions")
    public ApiResponse<PageResponse<PromotionResponse>> list(@AuthenticationPrincipal UUID ownerId,
                                                             @PathVariable UUID stadiumId,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(
                promotionService.getPromotions(stadiumId, ownerId, pageable), Function.identity()));
    }

    @PutMapping("/api/owner/promotions/{id}/deactivate")
    public ApiResponse<Void> deactivate(@AuthenticationPrincipal UUID ownerId,
                                        @PathVariable UUID id) {
        promotionService.deactivatePromotion(id, ownerId);
        return ApiResponse.ok(null, "Promotion deactivated");
    }

    // ---- Public ----

    @GetMapping("/api/stadiums/{stadiumId}/promotions")
    public ApiResponse<List<PromotionResponse>> publicList(@PathVariable UUID stadiumId) {
        return ApiResponse.ok(promotionService.getActivePromotions(stadiumId));
    }
}
