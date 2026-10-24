package uz.minipolya.minipolya_backend.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.minipolya.minipolya_backend.dto.request.AddCardRequest;
import uz.minipolya.minipolya_backend.dto.request.ConfirmCardRequest;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.dto.response.CardBindingResponse;
import uz.minipolya.minipolya_backend.dto.response.SavedCardResponse;
import uz.minipolya.minipolya_backend.service.SavedCardService;

import java.util.List;
import java.util.UUID;

/**
 * The player's saved cards. Binding is two calls: POST /api/cards starts it and
 * returns a binding id, POST /api/cards/confirm finishes it with the SMS code.
 */
@RestController
@RequestMapping("/api/cards")
@RequiredArgsConstructor
public class CardController {

    private final SavedCardService savedCardService;

    @PostMapping
    public ApiResponse<CardBindingResponse> add(@AuthenticationPrincipal UUID userId,
                                                @Valid @RequestBody AddCardRequest request) {
        return ApiResponse.ok(savedCardService.addCard(userId, request), "SMS code sent");
    }

    @PostMapping("/confirm")
    public ApiResponse<SavedCardResponse> confirm(@AuthenticationPrincipal UUID userId,
                                                  @Valid @RequestBody ConfirmCardRequest request) {
        return ApiResponse.ok(savedCardService.confirmCard(userId, request), "Card saved");
    }

    @GetMapping
    public ApiResponse<List<SavedCardResponse>> myCards(@AuthenticationPrincipal UUID userId) {
        return ApiResponse.ok(savedCardService.getMyCards(userId));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal UUID userId, @PathVariable UUID id) {
        savedCardService.deleteCard(userId, id);
        return ApiResponse.ok(null, "Card deleted");
    }
}
