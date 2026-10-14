# MiniPolya Backend

Football pitch booking platform for Telegram Mini App. Built for the Uzbekistan market (Tashkent).

## Tech Stack

- Java 21 (Amazon Corretto 21.0.4)
- Spring Boot 4.1.1
- PostgreSQL 15+
- Maven
- Lombok
- Spring Security + JWT
- spring-dotenv for environment variables
- Liquibase for database migrations
- MapStruct for Entity ↔ DTO mapping

## Project Structure

```
src/main/java/uz/minipolya/minipolya_backend/
├── config/                  # Security, JWT, CORS, app properties config
│   ├── SecurityConfig.java
│   ├── JwtProvider.java
│   ├── JwtAuthFilter.java
│   ├── AppProperties.java          # @ConfigurationProperties for app.*
│   └── CorsConfig.java
├── controller/              # REST controllers (thin — delegate to services)
│   ├── AuthController.java
│   ├── StadiumController.java
│   ├── BookingController.java
│   ├── GameController.java
│   ├── TeamController.java
│   ├── ReviewController.java
│   └── admin/
│       ├── AdminUserController.java
│       ├── AdminStadiumController.java
│       └── AdminStatsController.java
├── service/                 # Business logic (all writes are @Transactional)
│   ├── AuthService.java
│   ├── UserService.java
│   ├── StadiumService.java
│   ├── BookingService.java
│   ├── GameService.java
│   ├── TeamService.java
│   ├── PaymentService.java
│   ├── ReviewService.java
│   ├── NotificationService.java
│   └── PricingService.java
├── repository/              # Spring Data JPA repositories
│   ├── UserRepository.java
│   ├── StadiumRepository.java
│   ├── BookingRepository.java
│   ├── GameRepository.java
│   ├── GameParticipantRepository.java
│   ├── TeamRepository.java
│   ├── PaymentRepository.java
│   ├── ReviewRepository.java
│   ├── NotificationRepository.java
│   └── ...
├── entity/                  # JPA entities — one per database table
│   ├── User.java
│   ├── Stadium.java
│   ├── StadiumPhoto.java
│   ├── StadiumAmenities.java
│   ├── PricingRule.java
│   ├── Booking.java
│   ├── BookingSeries.java
│   ├── Payment.java
│   ├── Team.java
│   ├── TeamMember.java
│   ├── Game.java
│   ├── GameParticipant.java
│   ├── GameMessage.java
│   ├── Review.java
│   ├── Favorite.java
│   ├── StadiumClosure.java
│   ├── Promotion.java
│   ├── Notification.java
│   ├── OwnerApplication.java
│   └── AuditLog.java
├── dto/
│   ├── request/             # Incoming — validated with @Valid
│   │   ├── TelegramAuthRequest.java
│   │   ├── CreateBookingRequest.java
│   │   ├── CreateGameRequest.java
│   │   ├── CreateStadiumRequest.java
│   │   ├── CreateTeamRequest.java
│   │   ├── UpdatePricingRequest.java
│   │   └── ...
│   └── response/            # Outgoing — no entity leaks to API
│       ├── UserResponse.java
│       ├── StadiumResponse.java
│       ├── StadiumDetailResponse.java
│       ├── BookingResponse.java
│       ├── GameResponse.java
│       ├── AvailabilityResponse.java
│       ├── ApiResponse.java         # Generic wrapper: { success, data, message }
│       └── ...
├── exception/               # Custom exceptions + global handler
│   ├── SlotAlreadyBookedException.java
│   ├── SlotNotAvailableException.java
│   ├── BookingExpiredException.java
│   ├── UnauthorizedException.java
│   ├── ResourceNotFoundException.java
│   ├── ForbiddenException.java
│   └── GlobalExceptionHandler.java  # @RestControllerAdvice
├── scheduler/               # @Scheduled cron jobs
│   ├── BookingExpiryJob.java        # Expire unpaid bookings every minute
│   ├── GameTimeoutJob.java          # Cancel unfilled games at start time
│   ├── ReminderJob.java             # Send reminders 2h before game
│   ├── BookingSeriesJob.java        # Generate next week's recurring bookings
│   └── PromotionExpiryJob.java      # Mark expired promotions
├── telegram/                # Bot webhook
│   ├── TelegramWebhookController.java
│   └── TelegramBotService.java
├── mapper/                  # MapStruct mappers — Entity ↔ DTO (interfaces, auto-generated impls)
│   ├── UserMapper.java
│   ├── StadiumMapper.java
│   ├── BookingMapper.java
│   ├── GameMapper.java
│   └── ...
└── util/                    # Pure helper functions
    ├── RefundCalculator.java
    ├── PriceCalculator.java
    ├── TelegramAuthValidator.java
    └── TimeUtils.java
```

## Database

- **Migrations**: Managed by Liquibase
  - Master changelog: `src/main/resources/db/changelog/db.changelog-master.yaml`
  - Changeset files: `src/main/resources/db/changelog/changes/*.sql` (17 files, 20 tables)
  - Format: SQL changesets with `--liquibase formatted sql` header
  - Each changeset has a unique `--changeset minipolya:<id>` tag
  - Liquibase tracks applied changesets in `DATABASECHANGELOG` and `DATABASECHANGELOGLOCK` tables
  - **NEVER edit an already-applied changeset** — always create a new changeset file for schema changes
    - One-time exception, already taken: changesets 001–017 were edited in place once to convert all ids from BIGINT to UUID, before the first deployment, and the local dev database was dropped and recreated. The "never edit an applied changeset" rule applies again from now on — any further schema change is a new changeset (018+).
- Hibernate: `ddl-auto: validate` — Hibernate validates against existing schema, NEVER creates or alters tables
- **All primary keys are UUID** (PostgreSQL `UUID` type). UUIDs are generated application-side by Hibernate as **UUID v7** (`@UuidGenerator(style = UuidGenerator.Style.VERSION_7)` on each entity's `@Id private UUID id;`), with a DB-side `DEFAULT gen_random_uuid()` as a fallback. All foreign-key and raw entity-id columns are `UUID` too. The only non-UUID id is `users.telegram_id` (Telegram's own id, stays `BIGINT`); `payments.provider_txn_id` stays `VARCHAR`.
- All money: integer so'm (never use float/double for money)
- All timestamps: TIMESTAMPTZ stored in UTC, convert to `Asia/Tashkent` only for display/response
- Double-booking prevention: PostgreSQL `EXCLUDE USING gist` constraint on bookings table — the database itself rejects overlapping bookings on the same stadium
- 1 stadium = 1 pitch (no separate pitches table)

### Changeset Files

Created incrementally — each changeset is written alongside its JPA entity during the build order step.

```
db/changelog/
├── db.changelog-master.yaml          # Master — includes all changesets in order
└── changes/
    ├── 001-extensions.sql            # btree_gist extension
    ├── 002-create-users.sql
    ├── 003-create-stadiums.sql       # + stadium_photos + stadium_amenities
    ├── 004-create-pricing-rules.sql
    ├── 005-create-booking-series.sql
    ├── 006-create-teams.sql          # + team_members
    ├── 007-create-games.sql
    ├── 008-create-bookings.sql       # EXCLUDE constraint + games FK
    ├── 009-create-payments.sql
    ├── 010-create-game-participants.sql
    ├── 011-create-game-messages.sql
    ├── 012-create-reviews-favorites.sql
    ├── 013-create-stadium-closures-promotions.sql
    ├── 014-create-notifications.sql
    ├── 015-create-owner-applications.sql
    ├── 016-create-audit-log.sql
    └── 017-create-triggers.sql       # set_updated_at() function + triggers
```

### Adding New Migrations

When you need to change the schema (add column, new table, alter constraint):
1. Create a new file: `changes/018-description.sql`
2. Use the header format:
   ```sql
   --liquibase formatted sql

   --changeset minipolya:018-description
   --comment: What this migration does
   ALTER TABLE ...;
   ```
3. Add it to `db.changelog-master.yaml`:
   ```yaml
   - include:
       file: db/changelog/changes/018-description.sql
   ```
4. Run `./mvnw spring-boot:run` — Liquibase auto-applies pending changesets on startup

## Key Business Rules

### Users & Roles

- Everyone registers with phone number
- Telegram ID linked when they open the Mini App
- Roles are boolean flags on the users table:
  - Everyone can book (player is the default, no flag needed)
  - `is_owner = true` → approved stadium owner, sees owner dashboard
  - `is_admin = true` → platform admin, sees admin panel
- One user can be both owner and player simultaneously
- An owner CANNOT book their own stadium through the player flow (use manual booking instead)
- 3 languages: uz (default), ru, en — stored in `user.language`

### Stadiums

- 1 stadium = 1 pitch (format, surface_type on stadiums table)
- Status flow: `pending` → `approved` → `blocked`
- Only `approved` stadiums visible to players
- Owner adds stadium → admin reviews → approves/rejects
- On approval: stadium status → approved, user.is_owner → true

### Bookings

- Source types:
  - `app` → player booked via Mini App
  - `manual` → owner added walk-in/phone booking (client_name, client_phone, is_walkin=true)
  - `game` → created automatically when a game is confirmed
- Duration: 1 or 2 hours (chosen by user)
- Max booking: 4 weeks ahead
- Hold: unpaid bookings set `expires_at = now + 10 minutes`, status = `pending`
- Expiry: `BookingExpiryJob` runs every minute, marks expired pending bookings as `expired`
- Status flow:
  - `pending` → `confirmed` (paid) → `completed`
  - `pending` → `expired` (unpaid timeout)
  - `confirmed` → `cancelled_by_user`
  - `confirmed` → `cancelled_by_owner` (always 100% refund)

### Cancellation Policy (configurable per stadium)

```java
// Each stadium has: full_refund_hours (default 24), partial_refund_hours (default 12), partial_refund_percent (default 50)
long hoursLeft = hours between now and booking.startsAt;

if (hoursLeft >= stadium.fullRefundHours)    → 100% refund
if (hoursLeft >= stadium.partialRefundHours) → stadium.partialRefundPercent% refund
else                                         → 0% refund

// Owner cancels → ALWAYS 100% refund, regardless of time left
```

Players MUST see the stadium's cancellation policy before confirming a booking.

### Recurring Bookings (booking_series)

- Player says "repeat every Thursday 20:00"
- Creates a `booking_series` row + bookings for the next 2 weeks
- `BookingSeriesJob` runs daily, generates next week's booking when the window needs extending
- Each generated booking gets the CURRENT price (price changes apply to future bookings)
- Cancelling one booking: only that row cancelled, series stays active
- Cancelling the series: `is_active = false`, all future bookings cancelled

### Games (3 separate flows)

**casual — play with friends:**
- Creator picks stadium + time, invites friends via Telegram deep link
- Game is NOT public, only accessible via link
- Each player pays `total_price / max_players` + service_fee
- Slot stays FREE until ALL players have paid
- When all paid → booking created automatically
- If someone else books the slot before that → game cancelled, all refunded

**open — find players:**
- Same as casual but game is PUBLIC, visible in the app
- Any user can pay and join open spots
- System assigns sides (a/b) when game confirms

**team_vs_team — team vs team:**
- Team A leader creates game, pays `creator_share_pct`% of pitch price (configurable, default 50%)
- Team B leader finds the game, pays remaining `(100 - creator_share_pct)`%
- When both leaders paid → booking created
- Leaders invite team members via link (members do NOT pay for the pitch)
- Chat available between teams for each game
- Service fee: `service_fee_per_person` (default 5000 so'm) charged to both leaders

**Common game rules:**
- `GameTimeoutJob` cancels `open` games where `starts_at <= now` and status is still `open`
- When a regular booking takes the slot, find any `open` game on that stadium/time → cancel it, refund all participants
- Game statuses: `open` → `confirmed` → `completed`
- Cancel statuses: `cancelled_slot_taken`, `cancelled_by_creator`, `cancelled_not_filled`

### Payments

- Providers: Payme, Click, cash (for manual bookings)
- `provider_txn_id` is UNIQUE — handles duplicate webhook calls (idempotent)
- Payment types: `booking`, `game_share`, `service_fee`, `refund`
- Refunds create a NEW payment row with negative amount and refund_reason
- Never mutate an existing payment row's amount

### Availability Calculation

To show free slots for a stadium on a given date:
1. Get the stadium's `open_time` and `close_time`
2. Cut into 1-hour slots
3. Remove slots that overlap any `pending` or `confirmed` booking
4. Remove slots that fall within a `stadium_closure`
5. For each remaining slot, calculate price from `pricing_rules` (match day_of_week + time range)
6. Mark slots that have a `waiting` game (show "Game looking for players" in UI)
7. Apply any active promotion discount

## Auth

### Telegram Mini App (primary)
1. Client sends raw `initData` string to `POST /api/auth/telegram`
2. Server verifies HMAC-SHA256 signature using bot token:
   - Create secret: `HMAC-SHA256("WebAppData", bot_token)`
   - Compute hash: `HMAC-SHA256(secret, data_check_string)`
   - Compare with `hash` from initData
3. Extract user info from verified data
4. Find or create user by `telegram_id`
5. Return JWT

### Telegram Login Widget (web dashboard)
- Same flow, different payload format
- Same JWT returned, same user record

### JWT
- Sent as `Authorization: Bearer <token>`
- Contains: userId, phone, isOwner, isAdmin
- Expiry: 24 hours
- `JwtAuthFilter` validates on every request, sets SecurityContext

### Endpoint Security
- `/api/auth/**` → public
- `/api/stadiums/**` GET → public (listing, detail, availability)
- `/api/bookings/**` → authenticated
- `/api/owner/**` → authenticated + isOwner check
- `/api/admin/**` → authenticated + isAdmin check
- `/api/games/**` → authenticated
- `/api/teams/**` → authenticated
- `/webhooks/**` → verify provider signature, no JWT

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
- Use constructor injection (Lombok `@RequiredArgsConstructor`), never `@Autowired` on fields
- Log important actions: booking created, payment received, game confirmed, errors
- Write unit tests for service layer, integration tests for controllers

## Environment Variables

Stored in `.env` (never committed to Git), loaded by spring-dotenv:

```
DB_URL=jdbc:postgresql://localhost:5432/minipolya
DB_USERNAME=postgres
DB_PASSWORD=...
JWT_SECRET=...
BOT_TOKEN=...
```

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

# Create database (Liquibase creates the tables, you just need the empty DB)
psql -U postgres -c "CREATE DATABASE minipolya;"

# Check Liquibase migration status
./mvnw liquibase:status

# Rollback last changeset (if needed)
./mvnw liquibase:rollbackCount -Dliquibase.rollbackCount=1
```

## Dependencies (pom.xml)

Required beyond Spring Initializr defaults:
- `me.paulschwarz:spring-dotenv:4.0.0` — loads .env file
- `org.liquibase:liquibase-core` — managed by Spring Boot (no version needed)
- `org.mapstruct:mapstruct:1.6.3` — MapStruct annotations
- `org.mapstruct:mapstruct-processor:1.6.3` — annotation processor (in `maven-compiler-plugin` `<annotationProcessorPaths>`)
- `org.projectlombok:lombok-mapstruct-binding:0.2.0` — required when using Lombok + MapStruct together

### MapStruct + Lombok Maven Config

```xml
<properties>
    <mapstruct.version>1.6.3</mapstruct.version>
    <lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>
</properties>

<dependencies>
    <dependency>
        <groupId>org.mapstruct</groupId>
        <artifactId>mapstruct</artifactId>
        <version>${mapstruct.version}</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-compiler-plugin</artifactId>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok</artifactId>
                        <version>${lombok.version}</version>
                    </path>
                    <path>
                        <groupId>org.projectlombok</groupId>
                        <artifactId>lombok-mapstruct-binding</artifactId>
                        <version>${lombok-mapstruct-binding.version}</version>
                    </path>
                    <path>
                        <groupId>org.mapstruct</groupId>
                        <artifactId>mapstruct-processor</artifactId>
                        <version>${mapstruct.version}</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </plugin>
    </plugins>
</build>
```

**Important**: Lombok must come BEFORE MapStruct in `<annotationProcessorPaths>` so Lombok generates getters/setters before MapStruct reads them.

## Build Order

Implement in this order. Each step should be working and tested before moving to the next.

**For every step**: write the Liquibase changeset SQL file → add it to `db.changelog-master.yaml` → write the JPA entity → write the MapStruct mapper + DTOs → write the repository → write the service → write the controller. Each step produces a complete, working vertical slice.

1. **Project setup** — `db.changelog-master.yaml` (empty initially), `001-extensions.sql` (btree_gist), AppProperties, SecurityConfig skeleton, ApiResponse wrapper, GlobalExceptionHandler
2. **Auth + Users** — `002-create-users.sql` changeset → User entity → UserMapper → UserRepository → AuthService (Telegram initData verification) → JwtProvider → JwtAuthFilter → AuthController → UserService → UserController (profile, language)
3. **Stadiums** — `003-create-stadiums.sql` changeset (stadiums + stadium_photos + stadium_amenities) → Stadium/StadiumPhoto/StadiumAmenities entities → StadiumMapper → StadiumRepository → StadiumService → StadiumController (CRUD for owners, public listing/detail/search for players)
4. **Pricing** — `004-create-pricing-rules.sql` changeset → PricingRule entity → PricingRuleMapper → PricingService, PriceCalculator utility
5. **Booking Series** — `005-create-booking-series.sql` changeset → BookingSeries entity (used later by recurring bookings, but table needed for FK)
6. **Teams** — `006-create-teams.sql` changeset (teams + team_members) → Team/TeamMember entities → TeamMapper → TeamRepository → TeamService → TeamController
7. **Games** — `007-create-games.sql` changeset → Game entity → GameMapper → GameRepository (booking_id FK deferred until bookings table exists)
8. **Bookings** — `008-create-bookings.sql` changeset (bookings table + EXCLUDE constraint + games.booking_id FK + indexes) → Booking entity → BookingMapper → BookingRepository → BookingService → BookingController → BookingExpiryJob
9. **Payments** — `009-create-payments.sql` changeset → Payment entity → PaymentMapper → PaymentRepository → PaymentService → Payme/Click webhook integration
10. **Game Participants** — `010-create-game-participants.sql` changeset → GameParticipant entity → GameService (all 3 flows: casual, open, team_vs_team) → GameController → GameTimeoutJob
11. **Game Chat** — `011-create-game-messages.sql` changeset → GameMessage entity → simple message endpoints
12. **Reviews & Favorites** — `012-create-reviews-favorites.sql` changeset → Review/Favorite entities → ReviewService → ReviewController
13. **Stadium Closures & Promotions** — `013-create-stadium-closures-promotions.sql` changeset → StadiumClosure/Promotion entities → PromotionExpiryJob
14. **Availability** — calculate and return free slots (uses bookings, closures, pricing_rules, promotions, games)
15. **Notifications** — `014-create-notifications.sql` changeset → Notification entity → NotificationService → Telegram bot messages → ReminderJob
16. **Owner Applications** — `015-create-owner-applications.sql` changeset → OwnerApplication entity → admin review flow
17. **Audit Log** — `016-create-audit-log.sql` changeset → AuditLog entity → log important actions
18. **Triggers** — `017-create-triggers.sql` changeset → set_updated_at() trigger function for users, stadiums, teams, games, bookings
19. **Manual bookings** — owner adds walk-in bookings to their schedule (uses existing bookings table)
20. **Recurring bookings** — BookingSeries service + BookingSeriesJob (uses existing booking_series table from step 5)
21. **Owner dashboard APIs** — schedule view, stats, closures, promotions
22. **Admin APIs** — user management, stadium approval, platform stats

## Important Notes

- The PostgreSQL EXCLUDE constraint catches double-booking at the DB level. When it fires, catch the `DataIntegrityViolationException` in the service and throw `SlotAlreadyBookedException`.
- When a new booking is created, check for any `waiting` games on the same stadium/time and cancel them with `cancelled_slot_taken` status, refunding all participants.
- All cron jobs should be idempotent — safe to run multiple times without side effects.
- Owner's manual bookings and app bookings share the same `bookings` table and the same overlap constraint. This guarantees a unified, conflict-free schedule.
- Liquibase runs automatically on application startup. If a migration fails, the app won't start — fix the changeset and restart.
- Never modify an already-applied changeset. Liquibase checksums will catch it and refuse to start. Always create a new changeset file for changes.
