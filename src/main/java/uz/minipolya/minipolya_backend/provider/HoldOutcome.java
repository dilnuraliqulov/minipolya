package uz.minipolya.minipolya_backend.provider;

import java.time.OffsetDateTime;

/**
 * Where a hold stands: created (PENDING until confirmed), HELD, CAPTURED, RELEASED, or
 * FAILED (a declined card) with the provider's code.
 *
 * @param holdTill   when an unsettled hold lapses at the provider, or null
 * @param commission tiyin taken on capture, or null
 */
public record HoldOutcome(String holdId, ProviderStatus status, OffsetDateTime holdTill,
                          Long commission, String errorCode) {

    public boolean failed() {
        return status == ProviderStatus.FAILED;
    }
}
