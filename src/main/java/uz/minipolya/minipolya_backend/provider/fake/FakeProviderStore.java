package uz.minipolya.minipolya_backend.provider.fake;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Where FakePaymentProvider keeps what a real provider would keep on its side: bindings,
 * cards and their balances, charges, holds and reverses. Dumb storage — every rule lives
 * in the provider.
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

    /** Locked for the rest of the transaction where the store can lock. */
    Optional<FakeCard> card(String cardId);

    /** Inserts or updates the operation and its splits. */
    void saveOperation(FakeOperation operation);

    Optional<FakeOperation> operation(String id);

    Optional<FakeOperation> operationByAccount(FakeOperation.Kind kind, String account);

    /** The operation one of whose splits has this transaction id. */
    Optional<FakeOperation> operationByTransaction(String transactionId);

    /** The operation one of whose splits has this success transaction id. */
    Optional<FakeOperation> operationBySuccessId(String successTransId);

    void saveReverse(FakeReverse reverse);

    // ---- The dev view, newest first ----

    List<FakeCard> latestCards(int limit);

    List<FakeOperation> latestOperations(FakeOperation.Kind kind, int limit);

    List<FakeReverse> latestReverses(int limit);
}
