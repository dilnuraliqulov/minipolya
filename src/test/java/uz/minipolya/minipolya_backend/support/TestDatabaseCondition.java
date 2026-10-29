package uz.minipolya.minipolya_backend.support;

import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

/**
 * Runs a {@link DatabaseTest} only when the test PostgreSQL database can be reached,
 * creating it on first use. The database is {@code TEST_DB_URL} (default
 * {@code jdbc:postgresql://localhost:5432/minipolya_test}) with the credentials of
 * {@code DB_USERNAME} / {@code DB_PASSWORD}, from the environment or {@code .env}.
 * Liquibase builds the schema when the Spring context starts. It is never the dev
 * database: the url is separate on purpose.
 */
public class TestDatabaseCondition implements ExecutionCondition {

    public static final String DEFAULT_URL = "jdbc:postgresql://localhost:5432/minipolya_test";

    private static Boolean available;
    private static String reason;

    @Override
    public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
        synchronized (TestDatabaseCondition.class) {
            if (available == null) {
                probe();
            }
        }
        return available
                ? ConditionEvaluationResult.enabled("test database reachable")
                : ConditionEvaluationResult.disabled("test database unavailable: " + reason);
    }

    private static void probe() {
        Properties env = dotEnv();
        String url = setting("TEST_DB_URL", env, DEFAULT_URL);
        String user = setting("DB_USERNAME", env, "postgres");
        String password = setting("DB_PASSWORD", env, "");
        try (Connection ignored = DriverManager.getConnection(url, user, password)) {
            available = true;
        } catch (SQLException missing) {
            available = createDatabase(url, user, password);
            if (!available) {
                reason = missing.getMessage();
            }
        }
    }

    /** Connects to the server's {@code postgres} database and creates the test one. */
    private static boolean createDatabase(String url, String user, String password) {
        int slash = url.lastIndexOf('/');
        if (slash < 0) {
            return false;
        }
        String name = url.substring(slash + 1).replaceAll("[?].*$", "");
        if (!name.matches("[a-z0-9_]+") || !name.contains("test")) {
            reason = "refusing to create a database whose name does not look like a test one: " + name;
            return false;
        }
        try (Connection admin = DriverManager.getConnection(url.substring(0, slash + 1) + "postgres", user, password);
             Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
            try (Connection ignored = DriverManager.getConnection(url, user, password)) {
                return true;
            }
        } catch (SQLException ex) {
            reason = ex.getMessage();
            return false;
        }
    }

    private static String setting(String key, Properties env, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            value = env.getProperty(key);
        }
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static Properties dotEnv() {
        Properties properties = new Properties();
        Path file = Path.of(".env");
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
            } catch (IOException ignored) {
                // no .env: the environment and the defaults decide
            }
        }
        return properties;
    }
}
