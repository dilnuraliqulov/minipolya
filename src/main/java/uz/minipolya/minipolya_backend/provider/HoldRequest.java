package uz.minipolya.minipolya_backend.provider;

import java.util.List;

/**
 * A split hold on a saved card for {@code durationMinutes}; after that the provider lets
 * it go and it can no longer be captured. {@code account} is our payment row id.
 */
public record HoldRequest(List<Split> splits, String cardToken, String account, int durationMinutes) {

    public HoldRequest {
        if (splits == null || splits.isEmpty()) {
            throw new IllegalArgumentException("a hold needs at least one split");
        }
        if (durationMinutes < 1) {
            throw new IllegalArgumentException("durationMinutes must be at least 1");
        }
        splits = List.copyOf(splits);
    }

    public long total() {
        return splits.stream().mapToLong(Split::amount).sum();
    }

    /** Never render the token. */
    @Override
    public String toString() {
        return "HoldRequest(account=" + account + ", total=" + total() + ", minutes=" + durationMinutes + ")";
    }
}
