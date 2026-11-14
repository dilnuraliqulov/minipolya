package uz.minipolya.minipolya_backend.config.env;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;

/**
 * On a deployed server, refuses to start with a required setting missing — and says which
 * ones and what each is, in one message, before anything else is built.
 *
 * <p>Without this the first missing value surfaces as
 * {@code Could not resolve placeholder 'DB_URL' in value "${DB_URL}"} somewhere inside a
 * long stack trace, one variable at a time, and a missing {@code CARD_TOKEN_KEY} would be
 * found only when the first card is saved. Here the whole list arrives at once, each line
 * saying what the value is and where it comes from.
 *
 * <p>It only speaks up when a <em>deployment</em> profile is active ({@code staging},
 * {@code prod} or {@code atmos}). On a developer's machine and in the tests nothing
 * changes: those read their values from {@code .env} or set them as test properties, and a
 * check here would just get in the way.
 *
 * <p>It runs after {@link HostDatabaseEnvironmentPostProcessor}, so a {@code DB_URL} derived
 * from {@code DATABASE_URL} or the {@code PG*} variables counts as set.
 */
public class RequiredEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    /** Profiles that mean "this is a deployed server", where nothing may be missing. */
    static final Set<String> DEPLOYED_PROFILES = Set.of("staging", "prod", "atmos");

    /** One required setting: the variable name and what to put in it. */
    record Setting(String name, String hint) {
    }

    /** Required on every deployed server. */
    static final List<Setting> REQUIRED = List.of(
            new Setting("DB_URL", "the database's JDBC URL, e.g. "
                    + "jdbc:postgresql://postgres:5432/minipolya (the service name on the container network). "
                    + "Instead of this you may set DATABASE_URL "
                    + "(postgresql://user:password@host:port/database) or PGHOST/PGPORT/PGDATABASE."),
            new Setting("DB_USERNAME", "the database user. PGUSER, or DATABASE_URL's user info, is read instead."),
            new Setting("DB_PASSWORD", "that user's password. PGPASSWORD, or DATABASE_URL's user info, "
                    + "is read instead."),
            new Setting("JWT_SECRET", "at least 32 bytes of random text (openssl rand -base64 48), different "
                    + "from every other environment. Changing it signs everyone out."),
            new Setting("BOT_TOKEN", "the Telegram bot token from @BotFather. On a test server use a TEST "
                    + "bot, never production's: polling deletes that bot's webhook."),
            new Setting("CARD_TOKEN_KEY", "32 bytes, base64 (openssl rand -base64 32): encrypts saved card "
                    + "tokens at rest. Keep it — losing it makes every saved card unreadable."));

    /** Required on top of the above, per profile. */
    static final Map<String, List<Setting>> EXTRA_PER_PROFILE = Map.of(
            "staging", List.of(
                    new Setting("UPLOADS_DIR", "an absolute path on a mounted volume, e.g. /data/uploads. "
                            + "The default ./uploads lives inside the container, so every redeploy would take "
                            + "the stadium photos with it while their rows stayed in the database.")));

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        check(environment.getActiveProfiles(), environment::getProperty);
    }

    /** Runs after the database variables are derived, so a derived DB_URL counts as set. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    static void check(String[] activeProfiles, UnaryOperator<String> lookup) {
        List<String> deployed = Arrays.stream(activeProfiles)
                .filter(DEPLOYED_PROFILES::contains)
                .sorted()
                .toList();
        if (deployed.isEmpty()) {
            return;
        }

        Set<Setting> required = new LinkedHashSet<>(REQUIRED);
        for (String profile : deployed) {
            required.addAll(EXTRA_PER_PROFILE.getOrDefault(profile, List.of()));
        }
        List<Setting> missing = required.stream()
                .filter(setting -> {
                    String value = lookup.apply(setting.name());
                    return value == null || value.isBlank();
                })
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(message(deployed, missing));
        }
    }

    static String message(List<String> profiles, List<Setting> missing) {
        List<String> lines = new ArrayList<>();
        lines.add("");
        lines.add("The " + String.join(",", profiles) + " profile needs " + missing.size()
                + (missing.size() == 1 ? " setting that is not set:" : " settings that are not set:"));
        lines.add("");
        for (Setting setting : missing) {
            lines.add("  " + setting.name());
            lines.add("      " + setting.hint());
        }
        lines.add("");
        lines.add("Set each one as an environment variable on the service, then deploy again. The app "
                + "reads its configuration from the environment only: .env is for local development and "
                + "is never committed or deployed.");
        lines.add("");
        return String.join(System.lineSeparator(), lines);
    }
}
