package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.request.AddCardRequest;
import uz.minipolya.minipolya_backend.dto.request.ConfirmCardRequest;
import uz.minipolya.minipolya_backend.dto.request.ResendCardCodeRequest;
import uz.minipolya.minipolya_backend.dto.response.CardBindingResponse;
import uz.minipolya.minipolya_backend.dto.response.SavedCardResponse;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.CardBindingException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ProviderUnavailableException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.SavedCardMapper;
import uz.minipolya.minipolya_backend.provider.AtmosCodes;
import uz.minipolya.minipolya_backend.provider.BoundCard;
import uz.minipolya.minipolya_backend.provider.CardMask;
import uz.minipolya.minipolya_backend.provider.CardBindingStart;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.ProviderTimeoutException;
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
        CardBindingStart binding = binding(() -> paymentProvider.bindCardInit(request.getCardNumber(), request.getExpiry()));
        log.info("Card binding started for userId={} bindingId={}", userId, binding.bindingId());
        return toResponse(binding);
    }

    /** Sends the SMS code again, once the provider's cooldown has passed. */
    @Transactional(readOnly = true)
    public CardBindingResponse resendCode(UUID userId, ResendCardCodeRequest request) {
        requireUser(userId);
        CardBindingStart binding = binding(() -> paymentProvider.resendBindingCode(request.getBindingId()));
        log.info("Card binding code sent again for userId={} bindingId={}", userId, binding.bindingId());
        return toResponse(binding);
    }

    /** Step two: confirm with the SMS code and keep the token plus masked number. */
    @Transactional
    public SavedCardResponse confirmCard(UUID userId, ConfirmCardRequest request) {
        User user = requireUser(userId);
        BoundCard bound = binding(() -> paymentProvider.bindCardConfirm(request.getBindingId(), request.getSmsCode()));

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
        return CardMask.type(maskedPan);
    }

    private static CardBindingResponse toResponse(CardBindingStart binding) {
        return CardBindingResponse.builder()
                .bindingId(binding.bindingId())
                .maskedPhone(binding.maskedPhone())
                .build();
    }

    /**
     * Runs one binding call and turns the provider's refusal into an answer the player can
     * act on ({@link CardBindingException}, 400 with a code). An unreachable provider is 502:
     * nothing was bound, the player may simply try again. The provider's own code is
     * logged, never shown.
     */
    private static <T> T binding(java.util.function.Supplier<T> call) {
        try {
            return call.get();
        } catch (ProviderTimeoutException ex) {
            log.warn("Card binding call unanswered: {}", ex.getMessage());
            throw new ProviderUnavailableException(PROVIDER_UNAVAILABLE);
        } catch (PaymentProviderException ex) {
            log.info("Card binding refused by the provider: code={}", ex.getCode());
            if (AtmosCodes.isUnavailable(ex.getCode())) {
                throw new ProviderUnavailableException(PROVIDER_UNAVAILABLE);
            }
            throw bindingError(ex.getCode());
        }
    }

    static final String PROVIDER_UNAVAILABLE =
            "To'lov tizimi vaqtincha ishlamayapti. Birozdan so'ng qayta urinib ko'ring";

    static CardBindingException bindingError(String providerCode) {
        return switch (providerCode == null ? "" : providerCode) {
            case AtmosCodes.WRONG_CARD_NUMBER, "STPIMS-ERR-050" -> new CardBindingException(
                    "CARD_NOT_SUPPORTED", "Faqat Uzcard va Humo kartalari qabul qilinadi");
            case AtmosCodes.WRONG_EXPIRY, AtmosCodes.CARD_NOT_FOUND -> new CardBindingException(
                    "CARD_EXPIRY_INVALID", "Karta raqami yoki amal qilish muddati noto'g'ri");
            case AtmosCodes.CARD_EXPIRED -> new CardBindingException(
                    "CARD_EXPIRED", "Kartaning amal qilish muddati tugagan");
            case AtmosCodes.WRONG_OTP -> new CardBindingException(
                    "SMS_CODE_WRONG", "SMS kod noto'g'ri");
            case AtmosCodes.OTP_EXPIRED -> new CardBindingException(
                    "SMS_CODE_EXPIRED", "SMS kodning muddati tugagan. Kodni qayta yuboring");
            case AtmosCodes.OTP_RESEND_TOO_EARLY -> new CardBindingException(
                    "SMS_RESEND_TOO_EARLY", "Kodni qayta yuborish uchun biroz kuting");
            case AtmosCodes.RESEND_UNSUPPORTED -> new CardBindingException(
                    "SMS_RESEND_UNAVAILABLE", "Kodni qayta olish uchun karta ma'lumotlarini qaytadan kiriting");
            case AtmosCodes.BINDING_NOT_FOUND -> new CardBindingException(
                    "BINDING_NOT_FOUND", "Karta qo'shish jarayoni topilmadi. Qaytadan boshlang");
            default -> new CardBindingException(
                    "CARD_BINDING_FAILED", "Kartani qo'shib bo'lmadi. Boshqa karta bilan urinib ko'ring");
        };
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
