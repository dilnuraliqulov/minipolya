package uz.minipolya.minipolya_backend.provider.fake;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Where FakePaymentProvider keeps what a real provider would keep on its side: bindings,
 * cards and their balances. Dumb storage — every rule lives in the provider.
 *
 * <p>{@link #inTransaction} runs one provider call atomically and commits it on its own,
 * whatever the caller's transaction does afterwards: like ATMOS, the fake never forgets a
 * charge because our own transaction rolled back.
 */
public interface FakeProviderStore {

    <T> T inTransaction(Supplier<T> work);

    /** Inserts or updates. */
    void saveBinding(FakeBinding binding);

    Optional<FakeBinding> binding(String bindingId);

    /** Inserts or updates. */
    void saveCard(FakeCard card);

    /** Locked for the rest of the transaction where the store can lock. */
    Optional<FakeCard> cardByTokenHash(String tokenHash);

    Optional<FakeCard> card(String cardId);

    /** Newest first, for the dev view. */
    List<FakeCard> latestCards(int limit);
}
