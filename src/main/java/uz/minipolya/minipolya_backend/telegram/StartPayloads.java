package uz.minipolya.minipolya_backend.telegram;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The deep-link payload of a /start (e.g. {@code game_<id>} from a team invite) that came
 * before the user shared their phone, kept so the "open" button sent after the phone still
 * carries it. In memory for an hour: the app runs as one instance, and losing one on a
 * restart only means the Mini App opens on its home screen.
 */
@Component
public class StartPayloads {

    /** What Telegram allows in a start parameter. */
    static final Pattern VALID = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    static final Duration KEEP = Duration.ofHours(1);

    private record Entry(String payload, Instant expiresAt) {
    }

    private final Map<Long, Entry> pending = new ConcurrentHashMap<>();
    private final Clock clock;

    public StartPayloads(Clock clock) {
        this.clock = clock;
    }

    /** The payload if Telegram would accept it as a start parameter, else empty. */
    public static Optional<String> valid(String payload) {
        return payload != null && VALID.matcher(payload).matches() ? Optional.of(payload) : Optional.empty();
    }

    public void remember(long telegramId, String payload) {
        Instant now = clock.instant();
        pending.values().removeIf(entry -> entry.expiresAt().isBefore(now));
        pending.put(telegramId, new Entry(payload, now.plus(KEEP)));
    }

    /** The remembered payload, removed; empty when none or expired. */
    public Optional<String> take(long telegramId) {
        Entry entry = pending.remove(telegramId);
        if (entry == null || entry.expiresAt().isBefore(clock.instant())) {
            return Optional.empty();
        }
        return Optional.of(entry.payload());
    }
}
