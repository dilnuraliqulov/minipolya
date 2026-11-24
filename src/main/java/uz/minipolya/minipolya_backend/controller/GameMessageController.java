package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.SendGameMessageRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.GameMessageResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.service.GameMessageService;
import uz.minipolya.minipolya_backend.util.Pagination;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
public class GameMessageController {

    private final GameMessageService gameMessageService;

    @PostMapping("/api/games/{gameId}/messages")
    public ApiResponse<GameMessageResponse> send(@AuthenticationPrincipal UUID userId,
                                                 @PathVariable UUID gameId,
                                                 @Valid @RequestBody SendGameMessageRequest request) {
        return ApiResponse.ok(gameMessageService.sendMessage(gameId, userId, request.getMessage()), "Message sent");
    }

    /**
     * The chat, newest first. {@code before} (an ISO instant) asks for the history older
     * than it — what "load the earlier messages" means — and is left out on the first page.
     */
    @GetMapping("/api/games/{gameId}/messages")
    public ApiResponse<PageResponse<GameMessageResponse>> history(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID gameId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime before,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = Pagination.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return ApiResponse.ok(PageResponse.from(
                gameMessageService.getMessages(gameId, userId, before, pageable), Function.identity()));
    }
}
