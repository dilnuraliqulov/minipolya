# MiniPolya Backend

Football pitch booking platform for Telegram Mini App. Built for the Uzbekistan market
(Tashkent). This repository is the **backend only**: the REST API, the Telegram bot and the
uploaded photos it serves.

**This file holds what must never be broken, plus the structure, conventions and commands.
Every detail lives in `docs/claude/` — read the matching doc before you change an area.**

## Tech Stack

- Java 21 (Amazon Corretto 21.0.4)
- Spring Boot 4.1.1
- PostgreSQL 15+
- Maven
- Lombok
- Spring Security + JWT
- `.env` read by Spring itself via `spring.config.import` (no dotenv library)
- Liquibase for database migrations
- MapStruct for Entity ↔ DTO mapping

## Hard rules

Break none of these. Each is explained in full in the doc named beside it.

### Database and migrations — `docs/claude/database.md`

- **NEVER edit an already-applied Liquibase changeset.** Liquibase checksums it and the app
  refuses to start. Any schema change is a **new** file `changes/056-description.sql` with a
  unique `--changeset minipolya:<id>` tag, added to `db.changelog-master.yaml`. The one-time
  exception (001–017 edited in place before the first deploy) is spent.
- Hibernate is `ddl-auto: validate` — it never creates or alters a table. A failing migration
  stops startup; fix the changeset.
- **All primary keys are UUID**, generated application-side as **UUID v7**
  (`@UuidGenerator(style = VERSION_7)`). The only non-UUID id is `users.telegram_id`
  (Telegram's own, `BIGINT`); `payments.provider_txn_id` stays `VARCHAR`.
- **All money is `BIGINT` tiyin** (1 so'm = 100 tiyin) in the database, backend and API.
  **Never use float/double for money.** One unit everywhere; the frontend formats so'm.
- **All timestamps are `TIMESTAMPTZ` in UTC**, converted to `Asia/Tashkent` only for display.
  Tashkent calendar days always go through `util/TashkentDays`.

### Money and prices — `docs/claude/pricing.md`, `docs/claude/payments.md`

- **Players see ONE price.** The owner enters their own price; every amount shown to a player
  is the final amount they pay. **No breakdown field** ("card fee", "commission", "net",
  fee/advance/remainder splits) may appear in a player-facing response —
  `PriceFieldVisibilityTest` fails if one does.
- **One helper converts** owner → player price: `PriceCalculator.playerAmount`, applied with
  the settings in force by `CardPricing`. Integer math only; nothing else calculates it.
- **The platform takes nothing from ordinary bookings** — the team-game fee is its only money.
- Money rules live in the single-row `platform_settings` table, **not in config**; only timing
  lives in `app.*` properties.
- **There is no partial refund anywhere.** `PaymentService.refundInFull` is the only refund
  path (ATMOS cannot refund part of a transaction), so cancellation is all-or-nothing. Never
  add a refund-an-arbitrary-amount method.
- **Nothing is ever reversed twice.** Every give-back, refund, capture, return and
  reconciliation first locks the payment row and re-reads its *committed* status
  (`PaymentService.stillAsSeen`) before calling the provider.
- **Payment amounts never change after insert** — only statuses move forward; a refund is a
  new row with the negative gross.
- **`PaymentService.applySuccessfulBookingPayment` is the only path** from "money arrived" to
  "booking confirmed" (the one exception is a team game's join, which owns its booking).
- A declined card is never a 500; an unreachable provider is 502. An overlap error never
  travels back to the payment provider.

### Bookings and the schedule — `docs/claude/bookings.md`, `docs/claude/opening-hours.md`

- **A booking is exactly one hour** (`Booking.HOURS`). There is no multi-hour code path —
  don't add one.
- The PostgreSQL `EXCLUDE USING gist` constraint is the final arbiter of double-booking: catch
  `DataIntegrityViolationException` and throw `SlotAlreadyBookedException` (409).
- **The working day is the day whose opening started the hour**; hours after midnight belong
  to it. `util/WeeklyHours` is the one place that rule is expressed, `SlotRules` the one
  definition of a slot's status.
- All `@Scheduled` jobs must be **idempotent** — safe to run twice.

### Security and secrets — `docs/claude/users-and-auth.md`, `docs/claude/environment.md`

- **Never logged, returned or stored**: card numbers, expiry dates, SMS codes, card tokens,
  passwords or hashes, JWTs, the bot token, ATMOS credentials or access tokens. Request DTOs
  and provider records override `toString` to mask them, and tests check it.
- **Never pass a secret to the audit log**, as a field, a label or a reason.
  `AuditEntry.change` drops suspicious field names as a safety net, not as permission.
- **There is no API to set or change an admin password**, not even an admin's own — only the
  SQL in the doc. Don't add such an endpoint.
- `.env` is never committed and never deployed; real environment variables always win.
  Deployment profiles refuse to start when a required setting is missing.
- The dev sign-in (`/api/auth/dev/*`, `/api/dev/**`) exists **only** under `dev`, behind
  `X-Dev-Login-Key`; `FakePaymentProvider` never runs under `prod`/`atmos`; bot polling never
  runs outside `dev`/`staging`. Never enable any of them on a production server.
- **Exactly one instance** of the app runs: the scheduled jobs and `LoginRateLimiter` assume
  it. A second instance needs ShedLock and a shared rate limiter first.

### API shape — list conventions in `docs/claude/admin-api.md`

- **NEVER expose entities in API responses** — always map to a DTO, with MapStruct.
- Every endpoint answers the `ApiResponse` envelope below; absent fields are omitted.
- Admin/owner list filters are JPA specifications, never a JPQL `:x IS NULL OR …`; no N+1 on
  a list (one grouped query per extra thing, with a query-count test).

### Audit log — `docs/claude/audit-log.md`

- Every action that changes data writes **one** entry — admin, owner, player or system alike —
  with old → new values.
- **Action codes are stable**: they are stored and filtered on, so never rename one; add a new
  constant.
- **Entries can never be edited or deleted.** `AuditLog` is `@Immutable` and the repository
  exposes only `save` and reads. Don't add an update or delete path.

### Git workflow

- Work on a feature branch (`Feature/<thing>`, `Fix/<thing>`), open a PR, merge to `main` —
  never commit straight to `main`.
- **Commit or push only when asked.** End commit messages with the attribution line the
  session specifies.
- Never commit `.env`, credentials, uploads or anything secret. `docs/requirements-audit.md`
  is untracked on purpose.
- When an API field a client reads changes, write it up in
  `docs/frontend-field-changes-<date>.md` so the frontend repo can follow.

### The backend/frontend boundary

- The Mini App and the admin panel are a **separate repository**, checked out beside this one
  as `../minipolyaFrontend`. No frontend code or frontend docs here.
- The API serves JSON under `/api/**`, uploaded photos under `GET /api/uploads/**` and the
  Telegram webhook at `POST /webhooks/telegram` — the only public path under `/webhooks` (the
  unsigned Payme/Click callbacks are gone). One origin fronts both repos (Caddy), so
  `CORS_ALLOWED_ORIGINS` is normally empty.
- `deploy/` is the **only** copy of the deployment walkthrough; the frontend repository points
  at it rather than duplicating it.

### Other invariants

- The PostgreSQL EXCLUDE constraint catches double-booking at the DB level. When it fires, catch the `DataIntegrityViolationException` in the service and throw `SlotAlreadyBookedException`.
- When a new booking is created, check for any `waiting` games on the same stadium/time and cancel them with `cancelled_slot_taken` status, refunding all participants.
- All cron jobs should be idempotent — safe to run multiple times without side effects.
- Owner's manual bookings and app bookings share the same `bookings` table and the same overlap constraint. This guarantees a unified, conflict-free schedule.
- Liquibase runs automatically on application startup. If a migration fails, the app won't start — fix the changeset and restart.
- Never modify an already-applied changeset. Liquibase checksums will catch it and refuse to start. Always create a new changeset file for changes.


## Project Structure

Same names and notes as before, fewer lines: `·` separates files that share one line.

```
src/main/java/uz/minipolya/minipolya_backend/
├── config/                  # Security (incl. CORS from CORS_ALLOWED_ORIGINS), JWT, app properties, scheduling
│   ├── SecurityConfig.java · JwtProvider.java · JwtAuthFilter.java
│   ├── SchedulingConfig.java       # @EnableScheduling; app.scheduling.enabled=false turns the jobs off (DB tests)
│   ├── AppProperties.java          # @ConfigurationProperties for app.*
│   └── env/                        # HostDatabaseEnvironmentPostProcessor, RequiredEnvironmentPostProcessor
├── controller/              # REST controllers (thin — delegate to services)
│   ├── AuthController.java · StadiumController.java · BookingController.java
│   ├── GameController.java · TeamController.java · ReviewController.java
│   └── admin/                      # see "Admin panel API"
│       ├── AdminUserController.java        # users, legal details, a user's teams
│       ├── AdminStadiumController.java     # stadiums, status, internal notes
│       ├── AdminActivityController.java    # every booking and every game
│       ├── AdminPaymentController.java     # payments, summary, failed-refund recovery
│       └── AdminStatsController.java
├── service/                 # Business logic (all writes are @Transactional)
│   ├── AuthService.java · UserService.java · StadiumService.java · BookingService.java
│   ├── GameService.java · TeamService.java · PaymentService.java · ReviewService.java
│   ├── NotificationService.java · PricingService.java
│   └── TeamGameService.java        # The team_vs_team flow: holds, joins, the deadline bands, remainders
├── repository/              # Spring Data JPA repositories — one per entity (User, Stadium, Booking, Game,
│                            #   GameParticipant, Team, Payment, Review, Notification, …) + *Specifications
├── entity/                  # JPA entities — one per database table: User, Stadium, StadiumPhoto, StadiumAmenities,
│   │                        #   PricingRule, Booking, BookingSeries, Payment, Team, TeamMember, Game,
│   │                        #   GameParticipant, GameMessage, Review, Favorite, StadiumClosure, Promotion,
│   │                        #   Notification, OwnerApplication, AuditLog
│   └── GameTeam.java               # One team on one side of a team game: its fee, advance, remainder
├── dto/
│   ├── request/             # Incoming — validated with @Valid: TelegramAuthRequest, CreateBookingRequest,
│   │                        #   CreateGameRequest, CreateStadiumRequest, CreateTeamRequest, UpdatePricingRequest, …
│   └── response/            # Outgoing — no entity leaks to API: UserResponse, StadiumResponse,
│       │                    #   StadiumDetailResponse, BookingResponse, GameResponse, AvailabilityResponse, …
│       └── ApiResponse.java        # Generic wrapper: { success, data, message }
├── exception/               # Custom exceptions + global handler: SlotAlreadyBooked, SlotNotAvailable, BookingExpired,
│   │                        #   Unauthorized, ResourceNotFound, Forbidden, …
│   └── GlobalExceptionHandler.java # @RestControllerAdvice
├── provider/                # The payment provider behind one interface (see "Payments"): PaymentProvider,
│                            #   AtmosPaymentProvider, FakePaymentProvider, DeclineReason, AtmosCodes, CardMask
│                            #   (the Payme/Click hosted checkout is removed)
├── scheduler/               # @Scheduled cron jobs
│   ├── BookingExpiryJob.java        # Expire unpaid bookings every minute
│   ├── GameTimeoutJob.java          # Cancel unfilled games (team: 3h before start; others: at start)
│   ├── ReminderJob.java             # Send reminders 2h before game
│   ├── BookingSeriesJob.java        # Generate next week's recurring bookings
│   ├── PromotionExpiryJob.java      # Mark expired promotions
│   ├── PaymentReconcileJob.java     # Every 5 min: settle payments stuck mid-step (see "Payments")
│   └── TelegramUpdateCleanupJob.java # Daily: forget handled Telegram update ids older than a week
├── telegram/                # The bot (see "Telegram bot"); the sender itself is service/TelegramBotService
│   ├── TelegramWebhookController.java  # POST /webhooks/telegram, secret-checked
│   ├── TelegramPolling.java        # getUpdates loop, dev profile only
│   ├── TelegramUpdateHandler.java  # One entry point for both: dedupe by update_id, dispatch
│   ├── TelegramBotFlow.java        # /start, the contact, /help, /language
│   ├── TelegramBotSetup.java       # Description, commands, menu button on startup (only what differs)
│   ├── TelegramApi.java            # The raw Bot API client (mocked in tests)
│   ├── TelegramBanner.java         # The /start photo, file_id cached
│   ├── BotTexts.java               # Every bot text: resources/telegram/bot_{uz,ru,en}.properties
│   └── ...                         # StartPayloads, TelegramKeyboards, TelegramHtml, TelegramBotModeGuard
├── mapper/                  # MapStruct mappers — Entity ↔ DTO (interfaces, auto-generated impls):
│                            #   UserMapper, StadiumMapper, BookingMapper, GameMapper, …
├── audit/                   # Audit log: AuditEntry builder, AuditAction codes, labels (see "Audit log")
└── util/                    # Pure helper functions
    ├── CancellationPolicy.java · PriceCalculator.java · TelegramAuthValidator.java · TimeUtils.java
    ├── TeamPaymentQuote.java       # A team's minimum / full payment: player amounts, remainder, splits
    └── TeamGameSplit.java          # The old fee / advance / remainder arithmetic (no longer used for new teams)
```

## API Response Format

All endpoints return a consistent wrapper:

```json
{
  "success": true,
  "data": { ... },
  "message": null
}

{
  "success": false,
  "data": null,
  "message": "Slot is already booked"
}
```

For paginated lists:

```json
{
  "success": true,
  "data": {
    "content": [ ... ],
    "page": 0,
    "size": 20,
    "totalElements": 156,
    "totalPages": 8
  },
  "message": null
}
```

## Coding Conventions

- Use Lombok: `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor` on entities and DTOs
- Use `@Getter` `@Setter` instead of `@Data` on JPA entities (to avoid hashCode/equals issues)
- NEVER expose entities directly in API responses — always map to DTOs
- Use **MapStruct** for Entity ↔ DTO mapping:
  - Mapper interfaces in `mapper/` package, annotated with `@Mapper(componentModel = "spring")`
  - MapStruct generates implementations at compile time via annotation processor
  - Use `@Mapping` for field name mismatches, `@BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)` for partial updates
  - For nested objects (e.g., Stadium → StadiumResponse with owner name), use `@Mapping(source = "owner.name", target = "ownerName")` or custom `@Named` methods
  - Inject mappers with constructor injection like any Spring bean
- Service layer throws custom exceptions from `exception/` package
- `GlobalExceptionHandler` catches them and returns proper HTTP status + ApiResponse
- Repository methods use Spring Data JPA method naming: `findByStatusAndStartsAtBefore(...)`
- Use `@Transactional` on service methods that perform multiple writes
- Validate all incoming requests with `@Valid` on controller parameters
- Use `@ConfigurationProperties` for custom config (app.jwt.*, app.booking.*, etc.)
  - `app.game.opponent-deadline-hours` (default 3) is the team-game joining
    cutoff. Money rules live in `platform_settings`, not here — only timing does.
  - `app.admin-login.*` holds the admin password sign-in limits (see "Admin accounts").
  - `app.payments.fake.*` shapes the fake provider (see "Payments → The provider").
  - `app.payments.settle-now.*` is how long a request waits for a payment the provider gave
    no clear answer about before leaving it to `PaymentReconcileJob` (see `payments.md`,
    "A confirmation ATMOS could not answer").
- Use constructor injection (Lombok `@RequiredArgsConstructor`), never `@Autowired` on fields
- Log important actions: booking created, payment received, game confirmed, errors
- Write unit tests for service layer, integration tests for controllers


## Commands

```bash
# Build
./mvnw clean package

# Run (Liquibase auto-applies pending migrations on startup)
./mvnw spring-boot:run

# Run tests
./mvnw test

# Run specific test class
./mvnw test -Dtest=BookingServiceTest

# Run the Telegram bot locally, no public URL needed (polling; dev profile only). In .env:
# BOT_TOKEN of a TEST bot (never production's: polling deletes its webhook),
# TELEGRAM_BOT_MODE=polling, MINIAPP_URL=<an https URL, e.g. an ngrok tunnel to the Mini App>
SPRING_PROFILES_ACTIVE=dev ./mvnw spring-boot:run

# Database-backed tests use a separate database — see docs/claude/testing.md

# Create database (Liquibase creates the tables, you just need the empty DB)
psql -U postgres -c "CREATE DATABASE minipolya;"

# Check Liquibase migration status
./mvnw liquibase:status

# Rollback last changeset (if needed)
./mvnw liquibase:rollbackCount -Dliquibase.rollbackCount=1
```


## Detail docs — read the matching one BEFORE you change that area

Paths are relative to `docs/claude/`. Plain paths, deliberately **not** `@imports`: they are
not loaded into a session, so open the one you need. `README.md` there maps every old
CLAUDE.md section to its new file.

| Read it | When |
|---|---|
| `database.md` | before touching the schema, a changeset, an entity, or an id / money / timestamp type |
| `users-and-auth.md` | before touching sign-in (Telegram, dev, admin password), JWTs, roles, profiles, endpoint security |
| `stadiums.md` | before touching stadium creation, review and approval, districts, amenities, the public list |
| `opening-hours.md` | before touching opening hours, the weekly schedule, or anything asking "which day is this hour?" |
| `bookings.md` | before touching bookings, holds, paying, remainders, owner marks, cancellation, recurring series |
| `availability.md` | before touching the availability endpoint, slot statuses or the free-slot count |
| `pricing.md` | before touching any amount a player sees, the card fee, rounding, the minimum amount, settings |
| `payments.md` | before touching payments, the ATMOS or fake provider, declines, refunds, holds, cards, reconciliation |
| `atmos-endpoint-audit.md` | before changing any ATMOS request or reading its answer, and before asking ATMOS a question — it may be open there already |
| `teams.md` | before touching teams, members or invite links |
| `games.md` | before touching the casual or open game flow, or a rule shared by all three flows |
| `team-games.md` | before touching team games: holds, joins, the two deadlines, cancellation bands, remainders, the board |
| `audit-log.md` | before adding or changing an action that writes data, or reading / filtering the audit log |
| `admin-api.md` | before touching anything under `/api/admin/**`, including list filters and the revenue figures |
| `owner-api.md` | before touching anything under `/api/owner/**`, including the dashboard and stadium photos |
| `telegram-bot.md` | before touching the bot: webhook / polling, the conversation, bot texts or the banner |
| `environment.md` | before adding a setting, reading `.env`, or changing what a profile allows |
| `deployment.md` | before deploying, or changing the reverse proxy, HTTPS, secrets, backups, the Compose server |
| `testing.md` | before writing or running tests (the separate test database, `@DatabaseTest`) |
| `dependencies.md` | before adding a dependency or touching the MapStruct / Lombok annotation processors |
| `deferred.md` | before trusting owner revenue figures or casual-game share rounding |
| `build-order.md` | only for the original vertical-slice build order — history, not a to-do list |
