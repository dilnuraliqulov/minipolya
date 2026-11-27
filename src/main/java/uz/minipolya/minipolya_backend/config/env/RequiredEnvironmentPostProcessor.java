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
 * <p>Several may be active at once — {@code staging,atmos} is the test server talking to the
 * real ATMOS sandbox, {@code prod,atmos} is production — and then their requirements add up
 * and one message names every missing setting of all of them.
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
                            + "the stadium photos with it while their rows stayed in the database.")),
            // The four the real ATMOS client cannot make a single call without. They are
            // checked here, before any bean exists, as well as in
            // AtmosPaymentProvider.requireConfiguration, which sees the bound app.atmos.*
            // values. ATMOS_PLATFORM_RECIPIENT_ID is not among them — a sandbox is given one
            // later than the rest, and without it only the platform's own split of a team game
            // fails — but production needs it (below): there it would switch team games off.
            // Production must say which side of ATMOS it is on; AtmosEnvironmentGuard then
            // refuses to start it on anything but "production". Not asked for anywhere else:
            // an unset value means the sandbox, which is the safe end of that mistake.
            "prod", List.of(
                    new Setting("ATMOS_ENVIRONMENT", "exactly \"production\" on a production server. "
                            + "The ATMOS sandbox answers on the same host with credentials that look the "
                            + "same, so nothing else can tell them apart: left unset this server would "
                            + "quietly take nobody's money."),
                    new Setting("ATMOS_PLATFORM_RECIPIENT_ID", "the platform's own ATMOS recipient, where a "
                            + "team game's fee is paid. Without it every team game's creation and join is "
                            + "refused.")),
            "atmos", List.of(
                    new Setting("ATMOS_BASE_URL", "the ATMOS API root, e.g. https://apigw.atmos.uz — no "
                            + "trailing slash. ATMOS answers only calls from an IP it has allowed, so this "
                            + "works from the deployed server and not from a laptop."),
                    new Setting("ATMOS_CONSUMER_KEY", "the consumer key of the ATMOS application, from the "
                            + "ATMOS cabinet. With the secret it buys the access token (Basic auth, "
                            + "client_credentials)."),
                    new Setting("ATMOS_CONSUMER_SECRET", "that application's consumer secret. Never commit it, "
                            + "never log it, and use the sandbox's own pair on a test server."),
                    new Setting("ATMOS_STORE_ID", "the merchant store id payments are created, pre-applied, "
                            + "applied and looked up under.")));

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
