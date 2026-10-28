package uz.minipolya.minipolya_backend.provider;

/**
 * One recipient's part of a split card payment, in tiyin. The provider pays each split
 * to its recipient and takes its commission from each.
 *
 * <p>The owner's split names the stadium owner's ATMOS recipient
 * ({@code users.atmos_recipient_id}); the platform's split names none, and the provider
 * fills in the platform's own recipient from its configuration
 * ({@code ATMOS_PLATFORM_RECIPIENT_ID}).
 *
 * <p>TODO(ATMOS): the docs use {@code store_id} in the bulk-payment and hold requests but
 * {@code terminal_id} in the responses; which of the two identifies a recipient is an
 * open question.
 *
 * @param recipientId the owner's ATMOS recipient; null for the platform's split
 * @param amount      tiyin, positive
 * @param platform    true for the platform's own split
 */
public record Split(String recipientId, long amount, boolean platform) {

    public Split {
        if (amount <= 0) {
            throw new IllegalArgumentException("a split must be positive");
        }
    }

    public static Split owner(String recipientId, long amount) {
        return new Split(recipientId, amount, false);
    }

    public static Split platform(long amount) {
        return new Split(null, amount, true);
    }
}
