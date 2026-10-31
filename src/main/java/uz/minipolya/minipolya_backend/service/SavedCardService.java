package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.request.AddCardRequest;
import uz.minipolya.minipolya_backend.dto.request.ConfirmCardRequest;
import uz.minipolya.minipolya_backend.dto.response.CardBindingResponse;
import uz.minipolya.minipolya_backend.dto.response.SavedCardResponse;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.SavedCardMapper;
import uz.minipolya.minipolya_backend.provider.BoundCard;
import uz.minipolya.minipolya_backend.provider.CardBindingStart;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.repository.SavedCardRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.UUID;

/**
 * A player's saved cards. Binding is two steps: {@link #addCard} hands the card
 * number straight to the provider and gets back a binding id, then
 * {@link #confirmCard} exchanges that id plus the SMS code for a token and a
 * masked number — the only two things we store.
 *
 * <p>The card number never reaches this service's own state, the database or the
 * log. Deleting a card deactivates it so past payments remain explainable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SavedCardService {

    private final SavedCardRepository savedCardRepository;
    private final UserRepository userRepository;
    private final PaymentProvider paymentProvider;
    private final SavedCardMapper savedCardMapper;

    /**
     * Step one: ask the provider to start a binding. Nothing is persisted —
     * an abandoned binding simply expires at the provider.
     */
    @Transactional(readOnly = true)
    public CardBindingResponse addCard(UUID userId, AddCardRequest request) {
        requireUser(userId);
        CardBindingStart binding = paymentProvider.bindCardInit(request.getCardNumber(), request.getExpiry());
        log.info("Card binding started for userId={} bindingId={}", userId, binding.bindingId());
        return CardBindingResponse.builder()
                .bindingId(binding.bindingId())
                .maskedPhone(binding.maskedPhone())
                .build();
    }

    /** Step two: confirm with the SMS code and keep the token plus masked number. */
    @Transactional
    public SavedCardResponse confirmCard(UUID userId, ConfirmCardRequest request) {
        User user = requireUser(userId);
        BoundCard bound = paymentProvider.bindCardConfirm(request.getBindingId(), request.getSmsCode());

        SavedCard saved = savedCardRepository.save(SavedCard.builder()
                .user(user)
                .providerToken(bound.cardToken())
                .providerCardId(bound.cardId())
                .maskedNumber(bound.maskedPan())
                .expiry(bound.expiry())
                .cardType(cardTypeOf(bound.maskedPan()))
                .isActive(true)
                .build());

        log.info("Card saved: id={} userId={} masked={} type={}",
                saved.getId(), userId, saved.getMaskedNumber(), saved.getCardType());
        return savedCardMapper.toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<SavedCardResponse> getMyCards(UUID userId) {
        return savedCardRepository.findByUserIdAndIsActiveTrue(userId).stream()
                .map(savedCardMapper::toResponse)
                .toList();
    }

    /** Soft delete: the row stays so historical payments still make sense. */
    @Transactional
    public void deleteCard(UUID userId, UUID cardId) {
        SavedCard card = savedCardRepository.findById(cardId)
                .orElseThrow(() -> new ResourceNotFoundException("Card not found"));
        if (card.getUser() == null || !card.getUser().getId().equals(userId)) {
            throw new ForbiddenException("This card does not belong to you");
        }
        if (card.getProviderCardId() != null) {
            try {
                paymentProvider.removeCard(card.getProviderCardId(), card.getProviderToken());
            } catch (PaymentProviderException ex) {
                // The player asked for the card to be gone; it is unusable here either way.
                log.warn("Provider could not remove card {}: code={}", cardId, ex.getCode());
            }
        }
        card.setActive(false);
        savedCardRepository.save(card);
        log.info("Card deleted: id={} userId={}", cardId, userId);
    }

    /** uzcard (8600…), humo (9860…), or unknown — from the masked number's first digits. */
    static String cardTypeOf(String maskedPan) {
        String digits = maskedPan == null ? "" : maskedPan.replaceAll("\\D", "");
        if (digits.startsWith("8600")) {
            return "uzcard";
        }
        if (digits.startsWith("9860")) {
            return "humo";
        }
        return "unknown";
    }

    /**
     * An active card belonging to this user, for the payment flows. Kept here so
     * the ownership check lives in one place.
     */
    @Transactional(readOnly = true)
    public SavedCard requireOwnedCard(UUID userId, UUID cardId) {
        return savedCardRepository.findByIdAndUserIdAndIsActiveTrue(cardId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Card not found"));
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }
}
