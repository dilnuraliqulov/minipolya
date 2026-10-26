package uz.minipolya.minipolya_backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.StadiumResponse;
import uz.minipolya.minipolya_backend.service.FavoriteService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    @PostMapping("/api/favorites/{stadiumId}/toggle")
    public ApiResponse<Map<String, Boolean>> toggle(@AuthenticationPrincipal UUID userId,
                                                    @PathVariable UUID stadiumId) {
        boolean favorited = favoriteService.toggleFavorite(stadiumId, userId);
        return ApiResponse.ok(Map.of("favorited", favorited),
                favorited ? "Added to favorites" : "Removed from favorites");
    }

    @GetMapping("/api/favorites")
    public ApiResponse<PageResponse<StadiumResponse>> myFavorites(@AuthenticationPrincipal UUID userId,
                                                                  @RequestParam(defaultValue = "0") int page,
                                                                  @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(
                favoriteService.getFavorites(userId, pageable), Function.identity()));
    }
}
