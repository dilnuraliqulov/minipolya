# Testing

> Part of the MiniPolya CLAUDE.md doc set. **Read this before writing or running tests.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

```bash
# Run tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=BookingServiceTest
```

- Write unit tests for the service layer, integration tests for controllers.
- Database-backed tests (`@DatabaseTest`: full context, Liquibase schema, MockMvc, each test
  rolled back) run against a separate database, `TEST_DB_URL` (default
  `jdbc:postgresql://localhost:5432/minipolya_test`) with `DB_USERNAME`/`DB_PASSWORD`; it is
  created on first use, and the tests are skipped when it cannot be reached. **Never point
  `TEST_DB_URL` at the dev database.** Note: a dev server running with devtools restarts on
  every compile and applies new changesets to the dev database straight away.
- `@DatabaseTest` sets `app.bot.enabled=false`, so tests never message real chats with the
  token from `.env`, and `app.scheduling.enabled=false` turns the cron jobs off.
- `audit/Audited` (test sources) reads back what a mocked `AuditService` recorded —
  `Audited.one(auditService, AuditAction.X)`. See [audit-log.md](audit-log.md).
- Each admin list has a test asserting its query count with 20+ rows — see
  [admin-api.md](admin-api.md). `PriceFieldVisibilityTest` fails if a breakdown field leaks
  into a player view; see [pricing.md](pricing.md).
