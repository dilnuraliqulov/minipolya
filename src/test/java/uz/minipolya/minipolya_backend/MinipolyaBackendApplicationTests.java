package uz.minipolya.minipolya_backend;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Requires a running PostgreSQL and all Liquibase changesets (003-017); "
        + "re-enable once the full schema and a test datasource (e.g. Testcontainers) are wired.")
@SpringBootTest
class MinipolyaBackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
