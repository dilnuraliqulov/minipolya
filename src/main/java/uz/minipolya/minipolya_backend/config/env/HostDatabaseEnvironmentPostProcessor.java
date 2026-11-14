package uz.minipolya.minipolya_backend.config.env;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Fills in {@code DB_URL}, {@code DB_USERNAME} and {@code DB_PASSWORD} from the variables a
 * managed PostgreSQL hands out, for whichever of the three is not set directly.
 *
 * <p>A managed database does not describe itself the way Spring wants it. It publishes either
 * one URL — {@code DATABASE_URL=postgresql://user:password@host:5432/database}, which is not a
 * JDBC URL — or the libpq variables {@code PGHOST}, {@code PGPORT}, {@code PGDATABASE},
 * {@code PGUSER} and {@code PGPASSWORD}. Reading both here means the three variables the app
 * actually uses can be set in one of three ways, and the rest of the app keeps seeing only
 * {@code DB_URL} / {@code DB_USERNAME} / {@code DB_PASSWORD}:
 *
 * <ol>
 *   <li>set them yourself — what the Docker Compose deployment does, pointing {@code DB_URL}
 *       at the {@code postgres} service on the container network;</li>
 *   <li>set {@code DATABASE_URL} alone — what a managed database hands out;</li>
 *   <li>set the five {@code PG*} variables.</li>
 * </ol>
 *
 * <p>What is derived is added as the <em>lowest</em>-precedence property source, so a value
 * set directly always wins and nothing here can quietly point the app at another database.
 * It never guesses a host: with none of those variables present it adds nothing, and
 * {@link RequiredEnvironmentPostProcessor} is what says so in a readable way.
 */
public class HostDatabaseEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String SOURCE_NAME = "minipolya-database-from-host";
    private static final int DEFAULT_PORT = 5432;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> derived = derive(environment::getProperty);
        if (!derived.isEmpty()) {
            environment.getPropertySources().addLast(new MapPropertySource(SOURCE_NAME, derived));
        }
    }

    /** Runs last, so {@code application.yaml} and any {@code .env} have already been read. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }

    /** What the app's own three variables should be, for whichever of them is missing. */
    static Map<String, Object> derive(UnaryOperator<String> lookup) {
        Map<String, Object> derived = new LinkedHashMap<>();
        boolean needUrl = !hasText(lookup.apply("DB_URL"));
        boolean needUsername = !hasText(lookup.apply("DB_USERNAME"));
        boolean needPassword = !hasText(lookup.apply("DB_PASSWORD"));
        if (!needUrl && !needUsername && !needPassword) {
            return derived;
        }

        Parts fromUrl = parse(lookup.apply("DATABASE_URL"));
        String host = text(lookup.apply("PGHOST"));
        String database = text(lookup.apply("PGDATABASE"));

        if (needUrl) {
            if (fromUrl != null) {
                derived.put("DB_URL", fromUrl.jdbcUrl());
            } else if (host != null && database != null) {
                String port = text(lookup.apply("PGPORT"));
                derived.put("DB_URL", jdbcUrl(host, port == null ? String.valueOf(DEFAULT_PORT) : port, database, null));
            }
        }
        if (needUsername) {
            String username = fromUrl != null && fromUrl.username() != null
                    ? fromUrl.username()
                    : text(lookup.apply("PGUSER"));
            if (username != null) {
                derived.put("DB_USERNAME", username);
            }
        }
        if (needPassword) {
            String password = fromUrl != null && fromUrl.password() != null
                    ? fromUrl.password()
                    : text(lookup.apply("PGPASSWORD"));
            if (password != null) {
                derived.put("DB_PASSWORD", password);
            }
        }
        return derived;
    }

    /** A {@code postgres(ql)://user:password@host:port/database?query} URL, taken apart. */
    record Parts(String jdbcUrl, String username, String password) {
    }

    /** {@code null} for anything that is not a usable PostgreSQL URL — never a guess. */
    static Parts parse(String url) {
        if (!hasText(url)) {
            return null;
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException ex) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("postgres") || scheme.equals("postgresql"))) {
            return null;
        }
        String host = uri.getHost();
        String database = uri.getPath() == null ? "" : uri.getPath().replaceFirst("^/", "");
        if (!hasText(host) || !hasText(database)) {
            return null;
        }
        String port = uri.getPort() == -1 ? String.valueOf(DEFAULT_PORT) : String.valueOf(uri.getPort());

        String username = null;
        String password = null;
        // Raw, then decoded part by part: a password may contain a percent-encoded colon,
        // which splitting the decoded form would cut in the wrong place.
        String userInfo = uri.getRawUserInfo();
        if (hasText(userInfo)) {
            int colon = userInfo.indexOf(':');
            username = text(decode(colon < 0 ? userInfo : userInfo.substring(0, colon)));
            password = colon < 0 ? null : text(decode(userInfo.substring(colon + 1)));
        }
        return new Parts(jdbcUrl(host, port, database, uri.getRawQuery()), username, password);
    }

    private static String jdbcUrl(String host, String port, String database, String query) {
        // An IPv6 literal arrives without the brackets a JDBC URL needs around it.
        String hostPart = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "jdbc:postgresql://" + hostPart + ":" + port + "/" + database
                + (hasText(query) ? "?" + query : "");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String text(String value) {
        return hasText(value) ? value.trim() : null;
    }
}
