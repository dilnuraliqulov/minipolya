package uz.minipolya.minipolya_backend.provider;

import java.util.List;

/**
 * Where a charge ended up. A declined card is {@code FAILED} with the provider's code,
 * not an exception; {@code PENDING} means ask again later, never "failed".
 *
 * @param successTransIds what a reverse needs, one per split; empty unless SUCCESS
 * @param commission      tiyin the provider reports having taken, or null
 * @param ofdUrl          the fiscal receipt, or null
 * @param errorCode       the provider's code when FAILED (logged, never shown raw to players)
 */
public record ChargeOutcome(ProviderStatus status,
                            List<String> successTransIds,
                            Long commission,
                            String ofdUrl,
                            String errorCode) {

    public ChargeOutcome {
        successTransIds = successTransIds == null ? List.of() : List.copyOf(successTransIds);
    }

    public static ChargeOutcome failed(String errorCode) {
        return new ChargeOutcome(ProviderStatus.FAILED, List.of(), null, null, errorCode);
    }

    public static ChargeOutcome pending() {
        return new ChargeOutcome(ProviderStatus.PENDING, List.of(), null, null, null);
    }

    public boolean succeeded() {
        return status == ProviderStatus.SUCCESS;
    }
}
