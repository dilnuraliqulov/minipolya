package uz.minipolya.minipolya_backend.provider.fake;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The fake's state in memory, lost with the process. For unit tests of the fake itself;
 * the application uses {@link JdbcFakeProviderStore}. Objects are shared, not copied, so
 * a change made inside a failed call is not undone — the provider only changes state on
 * paths that then complete.
 */
public class InMemoryFakeProviderStore implements FakeProviderStore {

    private final Map<String, FakeBinding> bindings = new ConcurrentHashMap<>();
    private final Map<String, FakeCard> cards = new ConcurrentHashMap<>();
    private final Map<String, FakeOperation> operations = new ConcurrentHashMap<>();
    private final Map<String, FakeReverse> reverses = new ConcurrentHashMap<>();

    @Override
    public synchronized <T> T inTransaction(Supplier<T> work) {
        return work.get();
    }

    @Override
    public void saveBinding(FakeBinding binding) {
        bindings.put(binding.getBindingId(), binding);
    }

    @Override
    public Optional<FakeBinding> binding(String bindingId) {
        return Optional.ofNullable(bindings.get(bindingId));
    }

    @Override
    public void saveCard(FakeCard card) {
        cards.put(card.getCardId(), card);
    }

    @Override
    public Optional<FakeCard> cardByTokenHash(String tokenHash) {
        return cards.values().stream().filter(c -> c.getTokenHash().equals(tokenHash)).findFirst();
    }

    @Override
    public Optional<FakeCard> card(String cardId) {
        return Optional.ofNullable(cards.get(cardId));
    }

    @Override
    public void saveOperation(FakeOperation operation) {
        operations.put(operation.getId(), operation);
    }

    @Override
    public Optional<FakeOperation> operation(String id) {
        return Optional.ofNullable(operations.get(id));
    }

    @Override
    public Optional<FakeOperation> operationByAccount(FakeOperation.Kind kind, String account) {
        return operations.values().stream()
                .filter(o -> o.getKind() == kind && o.getAccount().equals(account))
                .findFirst();
    }

    @Override
    public Optional<FakeOperation> operationByTransaction(String transactionId) {
        return operations.values().stream()
                .filter(o -> o.getSplits().stream().anyMatch(s -> s.getTransactionId().equals(transactionId)))
                .findFirst();
    }

    @Override
    public Optional<FakeOperation> operationBySuccessId(String successTransId) {
        return operations.values().stream()
                .filter(o -> o.getSplits().stream().anyMatch(s -> successTransId.equals(s.getSuccessTransId())))
                .findFirst();
    }

    @Override
    public void saveReverse(FakeReverse reverse) {
        reverses.put(reverse.getId(), reverse);
    }

    @Override
    public List<FakeCard> latestCards(int limit) {
        return cards.values().stream()
                .sorted(Comparator.comparing(FakeCard::getCreatedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<FakeOperation> latestOperations(FakeOperation.Kind kind, int limit) {
        return operations.values().stream()
                .filter(o -> o.getKind() == kind)
                .sorted(Comparator.comparing(FakeOperation::getCreatedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public List<FakeReverse> latestReverses(int limit) {
        return reverses.values().stream()
                .sorted(Comparator.comparing(FakeReverse::getCreatedAt).reversed())
                .limit(limit)
                .toList();
    }
}
