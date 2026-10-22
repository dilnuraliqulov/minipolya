package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.CreateGameRequest;
import uz.minipolya.minipolya_backend.dto.request.JoinGameRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.GameDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.GameParticipantResponse;
import uz.minipolya.minipolya_backend.dto.response.GameResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.PaymentInitResponse;
import uz.minipolya.minipolya_backend.service.GameService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class GameController {

    private final GameService gameService;

    @PostMapping("/api/games")
    public ApiResponse<GameDetailResponse> create(@AuthenticationPrincipal UUID userId,
                                                  @Valid @RequestBody CreateGameRequest request) {
        return ApiResponse.ok(gameService.createGame(request, userId), "Game created");
    }

    @GetMapping("/api/games/{id}")
    public ApiResponse<GameDetailResponse> detail(@AuthenticationPrincipal UUID userId,
                                                  @PathVariable UUID id) {
        return ApiResponse.ok(gameService.getGameDetail(id, userId));
    }

    @GetMapping("/api/games/open")
    public ApiResponse<PageResponse<GameResponse>> openGames(@RequestParam(required = false) UUID stadiumId,
                                                             @RequestParam(defaultValue = "0") int page,
                                                             @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.ASC, "startsAt"));
        return ApiResponse.ok(PageResponse.from(gameService.getOpenGames(stadiumId, pageable), Function.identity()));
    }

    @GetMapping("/api/games/my")
    public ApiResponse<PageResponse<GameResponse>> myGames(@AuthenticationPrincipal UUID userId,
                                                           @RequestParam(defaultValue = "0") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "startsAt"));
        return ApiResponse.ok(PageResponse.from(gameService.getMyGames(userId, pageable), Function.identity()));
    }

    @PostMapping("/api/games/{id}/join")
    public ApiResponse<GameParticipantResponse> join(@AuthenticationPrincipal UUID userId,
                                                     @PathVariable UUID id,
                                                     @Valid @RequestBody(required = false) JoinGameRequest request) {
        String side = request == null ? null : request.getSide();
        UUID teamId = request == null ? null : request.getTeamId();
        return ApiResponse.ok(gameService.joinGame(id, userId, side, teamId), "Joined game");
    }

    @PostMapping("/api/games/{id}/pay")
    public ApiResponse<PaymentInitResponse> pay(@AuthenticationPrincipal UUID userId,
                                                @PathVariable UUID id,
                                                @RequestParam String provider) {
        return ApiResponse.ok(gameService.payForGame(id, userId, provider));
    }

    @PostMapping("/api/games/{id}/leave")
    public ApiResponse<Void> leave(@AuthenticationPrincipal UUID userId,
                                   @PathVariable UUID id) {
        gameService.leaveGame(id, userId);
        return ApiResponse.ok(null, "Left game");
    }

    @DeleteMapping("/api/games/{id}")
    public ApiResponse<Void> cancel(@AuthenticationPrincipal UUID userId,
                                    @PathVariable UUID id) {
        gameService.cancelGame(id, userId);
        return ApiResponse.ok(null, "Game cancelled");
    }
}
