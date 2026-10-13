package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.CreatePricingRuleRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdatePricingRuleRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PricingRuleResponse;
import uz.minipolya.minipolya_backend.service.PricingService;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class PricingController {

    private final PricingService pricingService;

    // ---- Owner ----

    @GetMapping("/api/owner/stadiums/{stadiumId}/pricing")
    public ApiResponse<List<PricingRuleResponse>> listForOwner(@AuthenticationPrincipal Long ownerId,
                                                               @PathVariable Long stadiumId) {
        return ApiResponse.ok(pricingService.listForOwner(ownerId, stadiumId));
    }

    @PostMapping("/api/owner/stadiums/{stadiumId}/pricing")
    public ApiResponse<PricingRuleResponse> create(@AuthenticationPrincipal Long ownerId,
                                                   @PathVariable Long stadiumId,
                                                   @Valid @RequestBody CreatePricingRuleRequest request) {
        return ApiResponse.ok(pricingService.create(ownerId, stadiumId, request), "Pricing rule created");
    }

    @PutMapping("/api/owner/stadiums/{stadiumId}/pricing/{id}")
    public ApiResponse<PricingRuleResponse> update(@AuthenticationPrincipal Long ownerId,
                                                   @PathVariable Long stadiumId,
                                                   @PathVariable Long id,
                                                   @Valid @RequestBody UpdatePricingRuleRequest request) {
        return ApiResponse.ok(pricingService.update(ownerId, stadiumId, id, request));
    }

    @DeleteMapping("/api/owner/stadiums/{stadiumId}/pricing/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal Long ownerId,
                                    @PathVariable Long stadiumId,
                                    @PathVariable Long id) {
        pricingService.softDelete(ownerId, stadiumId, id);
        return ApiResponse.ok(null, "Pricing rule removed");
    }

    // ---- Public (players) ----

    @GetMapping("/api/stadiums/{stadiumId}/pricing")
    public ApiResponse<List<PricingRuleResponse>> listPublic(@PathVariable Long stadiumId) {
        return ApiResponse.ok(pricingService.listPublic(stadiumId));
    }
}
