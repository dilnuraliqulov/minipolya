package uz.minipolya.minipolya_backend.provider;

/**
 * One charge or hold against a bound card. All amounts are tiyin, and
 * {@code amount} must equal {@code ownerAmount + platformAmount} — the split is
 * what the provider pays out to the stadium owner and keeps for the platform.
 *
 * @param cardToken     token from a confirmed {@link BoundCard}
 * @param amount        total to charge, in tiyin
 * @param ownerAmount   part of {@code amount} owed to the stadium owner
 * @param platformAmount part of {@code amount} the platform keeps
 * @param reference     our own unique reference for this attempt (idempotency key)
 */
public record ChargeCommand(String cardToken,
                            long amount,
                            long ownerAmount,
                            long platformAmount,
                            String reference) {
}
