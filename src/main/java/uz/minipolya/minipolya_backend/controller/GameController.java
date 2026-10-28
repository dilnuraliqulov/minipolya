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
import uz.minipolya.minipolya_backend.dto.request.GameCashRequest;
import uz.minipolya.minipolya_backend.dto.request.JoinGameRequest;
import uz.minipolya.minipolya_backend.dto.request.PayGameRemainderRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.GameChallengeResponse;
import uz.minipolya.minipolya_backend.dto.response.GameDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.GameParticipantResponse;
import uz.minipolya.minipolya_backend.dto.response.GameResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.dto.response.PaymentInitResponse;
import uz.minipolya.minipolya_backend.service.GameService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.time.LocalDate;
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

    /**
     * Open challenges: waiting team-vs-team games an opponent could still join.
     * Casual games are not challenges and are never listed here.
     */
    @GetMapping("/api/games/challenges")
    public ApiResponse<PageResponse<GameChallengeResponse>> challenges(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(required = false) LocalDate date,
            @RequestParam(required = false) String format,
            @RequestParam(required = false) UUID stadiumId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.ASC, "startsAt"));
        return ApiResponse.ok(PageResponse.from(
                gameService.getChallenges(userId, date, format, stadiumId, pageable), Function.identity()));
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
        UUID cardId = request == null ? null : request.getCardId();
        return ApiResponse.ok(gameService.joinGame(id, userId, side, teamId, cardId), "Joined game");
    }

    /**
     * A casual player's share. With {@code cardId}: charged now to that saved card through
     * ATMOS. Without it: the legacy hosted checkout of {@code provider} (payme/click).
     */
    @PostMapping("/api/games/{id}/pay")
    public ApiResponse<PaymentInitResponse> pay(@AuthenticationPrincipal UUID userId,
                                                @PathVariable UUID id,
                                                @RequestParam(required = false) String provider,
                                                @RequestParam(required = false) UUID cardId) {
        if (cardId != null) {
            return ApiResponse.ok(gameService.payForGameByCard(id, userId, cardId));
        }
        return ApiResponse.ok(gameService.payForGame(id, userId, provider));
    }

    @PostMapping("/api/games/{id}/pay-remainder")
    public ApiResponse<GameDetailResponse> payRemainder(@AuthenticationPrincipal UUID userId,
                                                        @PathVariable UUID id,
                                                        @Valid @RequestBody PayGameRemainderRequest request) {
        gameService.payTeamRemainder(id, userId, request.getCardId());
        return ApiResponse.ok(gameService.getGameDetail(id, userId), "Remainder paid");
    }

    @PostMapping("/api/games/{id}/cash")
    public ApiResponse<GameDetailResponse> cash(@AuthenticationPrincipal UUID userId,
                                                @PathVariable UUID id,
                                                @Valid @RequestBody GameCashRequest request) {
        gameService.recordTeamCashRemainder(id, userId, request.getSide());
        return ApiResponse.ok(gameService.getGameDetail(id, userId), "Cash recorded");
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
