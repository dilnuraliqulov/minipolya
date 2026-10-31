package uz.minipolya.minipolya_backend.provider;

import java.util.List;

/**
 * A split card charge. Either a token (a saved card) or a card number and expiry (a
 * one-off card, which then needs the cardholder's SMS code). {@code account} is our
 * payment row id: unique per attempt, so the provider never takes the same payment twice.
 * Fiscal receipt lines are the provider's business: it derives them from the splits.
 */
public record ChargeRequest(List<Split> splits,
                            String cardToken,
                            String cardNumber,
                            String expiry,
                            String account) {

    public ChargeRequest {
        if (splits == null || splits.isEmpty()) {
            throw new IllegalArgumentException("a charge needs at least one split");
        }
        if ((cardToken == null) == (cardNumber == null)) {
            throw new IllegalArgumentException("give either a card token or a card number, not both");
        }
        if (account == null || account.isBlank()) {
            throw new IllegalArgumentException("a charge needs its account (our payment id)");
        }
        splits = List.copyOf(splits);
    }

    public static ChargeRequest withToken(List<Split> splits, String cardToken, String account) {
        return new ChargeRequest(splits, cardToken, null, null, account);
    }

    public long total() {
        return splits.stream().mapToLong(Split::amount).sum();
    }

    /** Never render the token, number or expiry. */
    @Override
    public String toString() {
        return "ChargeRequest(account=" + account + ", total=" + total() + ", splits=" + splits.size() + ")";
    }
}
