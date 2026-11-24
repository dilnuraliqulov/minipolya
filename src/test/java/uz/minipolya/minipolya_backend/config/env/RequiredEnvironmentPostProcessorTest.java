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

    /** Everything any deployed profile asks for, so a test removes only what it is about. */
    private static final Map<String, String> COMPLETE = Map.ofEntries(
            Map.entry("DB_URL", "jdbc:postgresql://db.internal:5432/railway"),
            Map.entry("DB_USERNAME", "postgres"),
            Map.entry("DB_PASSWORD", "s3cret"),
            Map.entry("JWT_SECRET", "a-jwt-secret-of-at-least-32-bytes-012345"),
            Map.entry("BOT_TOKEN", "123:abc"),
            Map.entry("CARD_TOKEN_KEY", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
            Map.entry("UPLOADS_DIR", "/data/uploads"),
            Map.entry("ATMOS_BASE_URL", "https://apigw.atmos.uz"),
            Map.entry("ATMOS_CONSUMER_KEY", "a-consumer-key"),
            Map.entry("ATMOS_CONSUMER_SECRET", "a-consumer-secret"),
            Map.entry("ATMOS_STORE_ID", "777"));

    /** The four the atmos profile adds; the fifth variable, the recipient id, is not one. */
    private static final String[] ATMOS_SETTINGS =
            {"ATMOS_BASE_URL", "ATMOS_CONSUMER_KEY", "ATMOS_CONSUMER_SECRET", "ATMOS_STORE_ID"};

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

    /** The real sandbox on the test server: both profiles' requirements, one working server. */
    @Test
    void aCompleteStagingAtmosEnvironmentStarts() {
        assertThatCode(() -> check(COMPLETE, "staging", "atmos")).doesNotThrowAnyException();
    }

    @Test
    void theAtmosCredentialsAreRequiredWhereverAtmosIsActive() {
        for (String[] profiles : new String[][] {{"atmos"}, {"staging", "atmos"}, {"prod", "atmos"}}) {
            assertThatThrownBy(() -> check(without(ATMOS_SETTINGS), profiles))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("needs 4 settings that are not set")
                    .hasMessageContaining("ATMOS_BASE_URL")
                    .hasMessageContaining("ATMOS_CONSUMER_KEY")
                    .hasMessageContaining("ATMOS_CONSUMER_SECRET")
                    .hasMessageContaining("ATMOS_STORE_ID")
                    // Each line says what the value is and where it comes from.
                    .hasMessageContaining("https://apigw.atmos.uz")
                    .hasMessageContaining("ATMOS cabinet");
        }
    }

    /** Without the atmos profile nothing reads them, so nothing asks for them. */
    @Test
    void theAtmosCredentialsAreNotAskedForWithoutTheAtmosProfile() {
        assertThatCode(() -> check(without(ATMOS_SETTINGS), "staging")).doesNotThrowAnyException();
        assertThatCode(() -> check(without(ATMOS_SETTINGS), "prod")).doesNotThrowAnyException();
    }

    /**
     * The recipient id is the one ATMOS value a sandbox is given last. Missing, it fails only
     * the platform's own split of a team-game fee (AtmosPaymentProvider), never the start-up.
     */
    @Test
    void thePlatformRecipientIdNeverStopsTheStartup() {
        assertThatCode(() -> check(COMPLETE, "staging", "atmos")).doesNotThrowAnyException();
        assertThat(RequiredEnvironmentPostProcessor.EXTRA_PER_PROFILE.get("atmos"))
                .extracting(RequiredEnvironmentPostProcessor.Setting::name)
                .doesNotContain("ATMOS_PLATFORM_RECIPIENT_ID");
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
        assertThatThrownBy(() -> check(without("CARD_TOKEN_KEY", "UPLOADS_DIR", "ATMOS_STORE_ID"),
                "atmos", "staging"))
                .hasMessageContaining("The atmos,staging profile needs 3 settings")
                .hasMessageContaining("CARD_TOKEN_KEY")
                // staging's own extra and atmos's own extra, in the one message.
                .hasMessageContaining("UPLOADS_DIR")
                .hasMessageContaining("ATMOS_STORE_ID");
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
