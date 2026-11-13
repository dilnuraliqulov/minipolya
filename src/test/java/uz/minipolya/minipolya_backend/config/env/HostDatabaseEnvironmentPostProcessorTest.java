package uz.minipolya.minipolya_backend.config.env;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** A managed PostgreSQL's own variables become the three the app reads. */
class HostDatabaseEnvironmentPostProcessorTest {

    private static Map<String, Object> derive(Map<String, String> environment) {
        return HostDatabaseEnvironmentPostProcessor.derive(environment::get);
    }

    @Test
    void oneDatabaseUrlBecomesAJdbcUrlAUserAndAPassword() {
        Map<String, Object> derived = derive(Map.of(
                "DATABASE_URL", "postgresql://postgres:s3cret@postgres.railway.internal:5432/railway"));

        assertThat(derived).containsExactlyInAnyOrderEntriesOf(Map.of(
                "DB_URL", "jdbc:postgresql://postgres.railway.internal:5432/railway",
                "DB_USERNAME", "postgres",
                "DB_PASSWORD", "s3cret"));
    }

    @Test
    void theOlderPostgresSchemeAndAMissingPortWork() {
        assertThat(derive(Map.of("DATABASE_URL", "postgres://u:p@db.internal/railway")))
                .containsEntry("DB_URL", "jdbc:postgresql://db.internal:5432/railway");
    }

    @Test
    void aQueryStringIsKeptAndAnIpv6HostIsBracketed() {
        assertThat(derive(Map.of("DATABASE_URL", "postgresql://u:p@db.internal:5432/railway?sslmode=require")))
                .containsEntry("DB_URL", "jdbc:postgresql://db.internal:5432/railway?sslmode=require");
        assertThat(derive(Map.of("DATABASE_URL", "postgresql://u:p@[fd12::1]:5432/railway")))
                .containsEntry("DB_URL", "jdbc:postgresql://[fd12::1]:5432/railway");
    }

    @Test
    void aPercentEncodedPasswordIsDecodedWhole() {
        // The encoded colon must not split the user info in the wrong place.
        assertThat(derive(Map.of("DATABASE_URL", "postgresql://user:pa%3Ass%40word@db.internal:5432/railway")))
                .containsEntry("DB_PASSWORD", "pa:ss@word")
                .containsEntry("DB_USERNAME", "user");
    }

    @Test
    void thePgVariablesWorkToo() {
        assertThat(derive(Map.of(
                "PGHOST", "postgres.railway.internal",
                "PGPORT", "5433",
                "PGDATABASE", "railway",
                "PGUSER", "postgres",
                "PGPASSWORD", "s3cret")))
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "DB_URL", "jdbc:postgresql://postgres.railway.internal:5433/railway",
                        "DB_USERNAME", "postgres",
                        "DB_PASSWORD", "s3cret"));
    }

    @Test
    void aValueSetDirectlyIsNeverOverwritten() {
        Map<String, Object> derived = derive(Map.of(
                "DB_URL", "jdbc:postgresql://localhost:5432/minipolya",
                "DATABASE_URL", "postgresql://other:other@elsewhere:5432/other"));

        assertThat(derived).doesNotContainKey("DB_URL");
        // The two it was not given still come from the host.
        assertThat(derived).containsEntry("DB_USERNAME", "other").containsEntry("DB_PASSWORD", "other");
    }

    @Test
    void allThreeSetMeansNothingIsDerived() {
        assertThat(derive(Map.of(
                "DB_URL", "jdbc:postgresql://localhost:5432/minipolya",
                "DB_USERNAME", "postgres",
                "DB_PASSWORD", "pw",
                "DATABASE_URL", "postgresql://other:other@elsewhere:5432/other"))).isEmpty();
    }

    @Test
    void itNeverGuessesAHostOrADatabase() {
        assertThat(derive(Map.of())).isEmpty();
        // Not PostgreSQL, no database name, no host, unparseable: each adds nothing.
        assertThat(derive(Map.of("DATABASE_URL", "mysql://u:p@db.internal:3306/railway"))).isEmpty();
        assertThat(derive(Map.of("DATABASE_URL", "postgresql://u:p@db.internal:5432/"))).isEmpty();
        assertThat(derive(Map.of("DATABASE_URL", "postgresql:///railway"))).isEmpty();
        assertThat(derive(Map.of("DATABASE_URL", "not a url at all"))).isEmpty();
        // PGHOST without PGDATABASE is not enough for a URL, but still names the user.
        assertThat(derive(Map.of("PGHOST", "db.internal", "PGUSER", "postgres")))
                .doesNotContainKey("DB_URL")
                .containsEntry("DB_USERNAME", "postgres");
    }

    @Test
    void aBlankValueCountsAsUnset() {
        assertThat(derive(Map.of(
                "DB_URL", "   ",
                "DATABASE_URL", "postgresql://u:p@db.internal:5432/railway")))
                .containsEntry("DB_URL", "jdbc:postgresql://db.internal:5432/railway");
    }
}
