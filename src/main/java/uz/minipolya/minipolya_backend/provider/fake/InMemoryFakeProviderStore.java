package uz.minipolya.minipolya_backend.provider.fake;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The fake's state in memory, lost with the process. For unit tests of the fake itself;
 * the application uses {@code JdbcFakeProviderStore}.
 */
public class InMemoryFakeProviderStore implements FakeProviderStore {

    private final Map<String, FakeBinding> bindings = new ConcurrentHashMap<>();
    private final Map<String, FakeCard> cards = new ConcurrentHashMap<>();

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
    public List<FakeCard> latestCards(int limit) {
        return cards.values().stream()
                .sorted(Comparator.comparing(FakeCard::getCreatedAt).reversed())
                .limit(limit)
                .toList();
    }
}
