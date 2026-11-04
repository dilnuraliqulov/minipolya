package uz.minipolya.minipolya_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.OwnerContractStatusResponse;
import uz.minipolya.minipolya_backend.service.OwnerLegalDetailsService;

import java.util.UUID;

/** R39: the owner's own contract status, read-only. Admins keep the details themselves. */
@RestController
@RequiredArgsConstructor
public class OwnerLegalDetailsController {

    private final OwnerLegalDetailsService legalDetailsService;

    @GetMapping("/api/owner/legal-details")
    public ApiResponse<OwnerContractStatusResponse> myLegalDetails(@AuthenticationPrincipal UUID ownerId) {
        return ApiResponse.ok(legalDetailsService.getOwnStatus(ownerId));
    }
}
