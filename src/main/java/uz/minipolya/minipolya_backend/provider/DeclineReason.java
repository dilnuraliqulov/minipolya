package uz.minipolya.minipolya_backend.provider;

import lombok.extern.slf4j.Slf4j;

import java.util.Set;

/**
 * R44: why a card payment was refused, in the player's terms — the {@code code} of a 400
 * decline and the {@code declineReason} of a booking or payment. The one place the
 * provider's codes are mapped to it; the provider's own code is never shown to a player.
 */
@Slf4j
public enum DeclineReason {
    /** Not enough money on the card ({@code STPIMS-ERR-057}, {@code -112}). */
    INSUFFICIENT_FUNDS,
    /** The card has expired ({@code STPIMS-ERR-067}). */
    CARD_EXPIRED,
    /** Any other refusal — the fallback. */
    CARD_DECLINED;

    private static final Set<String> INSUFFICIENT = Set.of(
            AtmosCodes.INSUFFICIENT_FUNDS, AtmosCodes.INSUFFICIENT_BALANCE);

    /** Refusals we know, all of them a plain decline for the player. */
    private static final Set<String> KNOWN_DECLINES = Set.of(
            AtmosCodes.CARD_BLOCKED,        // карта заблокирована
            "STPIMS-ERR-079",               // карта не активирована
            "STPIMS-ERR-088",               // карта заблокирована
            AtmosCodes.WRONG_EXPIRY,        // карта не найдена / неверный срок
            AtmosCodes.CARD_NOT_FOUND,      // карта не найдена (e.g. removed)
            "STPIMS-ERR-043",               // банковская карта не найдена
            "STPIMS-ERR-030",               // карта не найдена в банке
            AtmosCodes.WRONG_CARD_NUMBER,
            "STPIMS-ERR-050",               // карта не может быть зарегистрирована
            AtmosCodes.WRONG_AMOUNT,        // e.g. under the minimum
            "STPIMS-ERR-042",               // не разрешенная сумма платежа
            AtmosCodes.WRONG_OTP);

    /**
     * The reason for a refusal the provider answered with {@code providerCode}. An unknown
     * code is a plain decline and is logged (the code only); no code at all — a payment that
     * failed without the provider refusing it — has no reason.
     */
    public static DeclineReason of(String providerCode) {
        if (providerCode == null || providerCode.isBlank()) {
            return null;
        }
        if (INSUFFICIENT.contains(providerCode)) {
            return INSUFFICIENT_FUNDS;
        }
        if (AtmosCodes.CARD_EXPIRED.equals(providerCode)) {
            return CARD_EXPIRED;
        }
        if (!KNOWN_DECLINES.contains(providerCode)) {
            log.warn("Unknown decline code from the payment provider, shown as CARD_DECLINED: code={}", providerCode);
        }
        return CARD_DECLINED;
    }
}
