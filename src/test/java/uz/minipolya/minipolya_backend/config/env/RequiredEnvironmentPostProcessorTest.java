package uz.minipolya.minipolya_backend.config.env;

import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.mock.env.MockEnvironment;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A deployed server says what is missing, at startup, all of it at once. */
class RequiredEnvironmentPostProcessorTest {

    private static final Map<String, String> COMPLETE = Map.of(
            "DB_URL", "jdbc:postgresql://db.internal:5432/railway",
            "DB_USERNAME", "postgres",
            "DB_PASSWORD", "s3cret",
            "JWT_SECRET", "a-jwt-secret-of-at-least-32-bytes-012345",
            "BOT_TOKEN", "123:abc",
            "CARD_TOKEN_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
            "UPLOADS_DIR", "/data/uploads");

    private static void check(Map<String, String> environment, String... profiles) {
        RequiredEnvironmentPostProcessor.check(profiles, environment::get);
    }

    private static Map<String, String> without(String... names) {
        Map<String, String> environment = new HashMap<>(COMPLETE);
        for (String name : names) {
            environment.remove(name);
        }
        return environment;
    }

    @Test
    void aCompleteStagingEnvironmentStarts() {
        assertThatCode(() -> check(COMPLETE, "staging")).doesNotThrowAnyException();
    }

    @Test
    void everyMissingSettingIsNamedInOneMessage() {
        assertThatThrownBy(() -> check(without("JWT_SECRET", "CARD_TOKEN_KEY", "UPLOADS_DIR"), "staging"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("The staging profile needs 3 settings that are not set")
                .hasMessageContaining("JWT_SECRET")
                .hasMessageContaining("CARD_TOKEN_KEY")
                .hasMessageContaining("UPLOADS_DIR")
                // Each line says what the value is, not just its name.
                .hasMessageContaining("openssl rand -base64 48")
                .hasMessageContaining("/data/uploads")
                .hasMessageContaining("environment variable");
    }

    @Test
    void oneMissingSettingReadsAsOne() {
        assertThatThrownBy(() -> check(without("BOT_TOKEN"), "staging"))
                .hasMessageContaining("needs 1 setting that is not set")
                .hasMessageContaining("BOT_TOKEN");
    }

    @Test
    void aBlankValueCountsAsMissing() {
        Map<String, String> blank = new HashMap<>(COMPLETE);
        blank.put("JWT_SECRET", "   ");

        assertThatThrownBy(() -> check(blank, "staging")).hasMessageContaining("JWT_SECRET");
    }

    @Test
    void uploadsDirIsRequiredOnStagingOnly() {
        // Staging is new, so requiring it there changes nothing that worked before; prod and
        // atmos keep exactly the refusals they had.
        assertThatThrownBy(() -> check(without("UPLOADS_DIR"), "staging")).hasMessageContaining("UPLOADS_DIR");
        assertThatCode(() -> check(without("UPLOADS_DIR"), "prod")).doesNotThrowAnyException();
        assertThatCode(() -> check(without("UPLOADS_DIR"), "prod", "atmos")).doesNotThrowAnyException();
    }

    @Test
    void nothingIsCheckedOnADeveloperMachineOrInTests() {
        assertThatCode(() -> check(Map.of())).doesNotThrowAnyException();
        assertThatCode(() -> check(Map.of(), "dev")).doesNotThrowAnyException();
        assertThatCode(() -> check(Map.of(), "default")).doesNotThrowAnyException();
    }

    @Test
    void severalProfilesAreAllNamedAndTheirRequirementsAddUp() {
        assertThatThrownBy(() -> check(without("CARD_TOKEN_KEY", "UPLOADS_DIR"), "atmos", "staging"))
                .hasMessageContaining("The atmos,staging profile needs 2 settings")
                .hasMessageContaining("UPLOADS_DIR");
    }

    @Test
    void itIsCheckedThroughTheEnvironmentItself() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");

        assertThatThrownBy(() -> new RequiredEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_URL");
    }

    /**
     * Both post-processors have to be named in META-INF/spring.factories, under the key
     * Spring Boot reads them by, or neither ever runs and nothing above is ever reached.
     * The key is the one spring-boot's own spring.factories uses for its post-processors.
     */
    @Test
    void bothPostProcessorsAreRegisteredWithSpringBoot() throws Exception {
        Properties factories = new Properties();
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream("META-INF/spring.factories")) {
            assertThat(resource).as("META-INF/spring.factories on the classpath").isNotNull();
            factories.load(resource);
        }

        String registered = factories.getProperty(EnvironmentPostProcessor.class.getName());

        assertThat(registered).as("the EnvironmentPostProcessor registrations")
                .isNotNull()
                .contains(HostDatabaseEnvironmentPostProcessor.class.getName())
                .contains(RequiredEnvironmentPostProcessor.class.getName());
    }

    /** The one that derives the database settings must run before the one that checks them. */
    @Test
    void theDatabaseSettingsAreDerivedBeforeTheyAreChecked() {
        assertThat(new HostDatabaseEnvironmentPostProcessor().getOrder())
                .isLessThan(new RequiredEnvironmentPostProcessor().getOrder());
    }

    /** A DB_URL derived from the host's own variables counts as set. */
    @Test
    void aDerivedDatabaseUrlSatisfiesTheCheck() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("staging");
        COMPLETE.forEach((name, value) -> {
            if (!name.startsWith("DB_")) {
                environment.setProperty(name, value);
            }
        });
        environment.setProperty("DATABASE_URL", "postgresql://postgres:s3cret@db.internal:5432/railway");
        SpringApplication application = new SpringApplication();

        new HostDatabaseEnvironmentPostProcessor().postProcessEnvironment(environment, application);

        assertThatCode(() -> new RequiredEnvironmentPostProcessor().postProcessEnvironment(environment, application))
                .doesNotThrowAnyException();
    }
}
