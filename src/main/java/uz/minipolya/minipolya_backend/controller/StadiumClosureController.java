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
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumClosureRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumClosureResponse;
import uz.minipolya.minipolya_backend.service.StadiumClosureService;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class StadiumClosureController {

    private final StadiumClosureService closureService;

    @PostMapping("/api/owner/stadiums/{stadiumId}/closures")
    public ApiResponse<StadiumClosureResponse> create(@AuthenticationPrincipal UUID ownerId,
                                                      @PathVariable UUID stadiumId,
                                                      @Valid @RequestBody CreateStadiumClosureRequest request) {
        request.setStadiumId(stadiumId); // path is authoritative
        return ApiResponse.ok(closureService.createClosure(request, ownerId), "Closure created");
    }

    @GetMapping("/api/owner/stadiums/{stadiumId}/closures")
    public ApiResponse<List<StadiumClosureResponse>> list(@AuthenticationPrincipal UUID ownerId,
                                                          @PathVariable UUID stadiumId) {
        return ApiResponse.ok(closureService.getClosures(stadiumId, ownerId));
    }

    @DeleteMapping("/api/owner/closures/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UUID ownerId,
                                    @PathVariable UUID id) {
        closureService.deleteClosure(id, ownerId);
        return ApiResponse.ok(null, "Closure removed");
    }
}
