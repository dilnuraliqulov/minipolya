# MiniPolya Backend

Football pitch booking platform for Telegram Mini App. Built for the Uzbekistan market (Tashkent).

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

## Project Structure

```
src/main/java/uz/minipolya/minipolya_backend/
├── config/                  # Security (incl. CORS from CORS_ALLOWED_ORIGINS), JWT, app properties, scheduling
│   ├── SecurityConfig.java
│   ├── JwtProvider.java
│   ├── JwtAuthFilter.java
│   ├── SchedulingConfig.java       # @EnableScheduling; app.scheduling.enabled=false turns the jobs off (DB tests)
│   └── AppProperties.java          # @ConfigurationProperties for app.*
├── controller/              # REST controllers (thin — delegate to services)
│   ├── AuthController.java
│   ├── StadiumController.java
│   ├── BookingController.java
│   ├── GameController.java
│   ├── TeamController.java
│   ├── ReviewController.java
│   └── admin/                      # see "Admin panel API"
│       ├── AdminUserController.java        # users, legal details, a user's teams
│       ├── AdminStadiumController.java     # stadiums, status, internal notes
│       ├── AdminActivityController.java    # every booking and every game
│       ├── AdminPaymentController.java     # payments, summary, failed-refund recovery
│       └── AdminStatsController.java
├── service/                 # Business logic (all writes are @Transactional)
│   ├── AuthService.java
│   ├── UserService.java
│   ├── StadiumService.java
│   ├── BookingService.java
│   ├── GameService.java
│   ├── TeamGameService.java        # The team_vs_team flow: holds, joins, the deadline bands, remainders
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
│   ├── GameTeam.java               # One team on one side of a team game: its fee, advance, remainder
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
├── mapper/                  # MapStruct mappers — Entity ↔ DTO (interfaces, auto-generated impls)
│   ├── UserMapper.java
│   ├── StadiumMapper.java
│   ├── BookingMapper.java
│   ├── GameMapper.java
│   └── ...
├── audit/                   # Audit log: AuditEntry builder, AuditAction codes, labels (see "Audit log")
└── util/                    # Pure helper functions
    ├── CancellationPolicy.java
    ├── TeamPaymentQuote.java       # A team's minimum / full payment: player amounts, remainder, splits
    ├── TeamGameSplit.java          # The old fee / advance / remainder arithmetic (no longer used for new teams)
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
- All money: BIGINT tiyin (1 so'm = 100 tiyin), in the database, backend and API (never use float/double for money). One unit everywhere — the frontend formats so'm for display.
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
    ├── 017-create-triggers.sql       # set_updated_at() function + triggers
    ├── ...                           # 018–024: duration, blocking, tiyin, settings, cards, payments, cancellation
    ├── 025-game-teams.sql            # game_teams + payments.game_team_id; drops games.creator_share_pct
    ├── 026-opponent-left.sql         # bookings status cancelled_opponent_left + notification game_opponent_left
    ├── 027-admin-password-login.sql  # pgcrypto + users.username/password_hash/lockout columns; audit_log.entity_id nullable
    ├── 028-password-changed-at.sql   # users.password_changed_at — tokens issued earlier are refused
    ├── 029-audit-log-structure.sql   # audit_log actor_type, actor_name, entity_label, changes, reason
    ├── 030-card-fee-settings.sql     # platform_settings card_fee_bps, payment_rounding_step
    ├── 031-card-fee-payments.sql     # payments card_fee_expected, provider_commission; bookings.card_price
    ├── 032-atmos-operations.sql      # processing status, provider ids, ofd_url, hold_expires_at; saved card id/expiry; users.atmos_recipient_id (moved by 037)
    ├── 033-payment-pending-steps.sql # return_pending / capture_pending statuses
    ├── 034-saved-card-token-encrypted.sql # provider_token widened for AES-GCM
    ├── 035-refund-failures.sql       # payments failure_reason, refunded_manually, manual_refund_*; refund_failed status
    ├── 036-stadium-district.sql      # stadiums.district (12 Tashkent districts, CHECK)
    ├── 037-owner-legal-details.sql   # owner_legal_details; moves users.atmos_recipient_id there and drops it
    ├── 038-stadium-rejected.sql      # rejected stadium status + status_reason; old rejections converted
    ├── 039-stadium-notes.sql         # stadium_notes (admins' internal notes)
    ├── 040-player-price.sql          # drops platform_settings.booking_commission_percent; bookings.pricing_model
    ├── 041-user-profile-names.sql    # users.first_name, users.last_name (set only by the user)
    ├── 042-telegram-bot.sql          # telegram_processed_updates (dedupe), telegram_bot_values (banner file_id)
    ├── 043-team-invite-code.sql      # teams.invite_code (unique, nullable) for invite links (R32)
    ├── 044-fake-payment-provider.sql # fake_payment_* tables: FakePaymentProvider's state (dev/tests only)
    ├── 045-booking-status-length.sql # bookings.status VARCHAR(30): 'cancelled_opponent_left' did not fit
    ├── 046-game-team-joining.sql     # game_teams status 'joining' (a join half-way); side index covers it
    ├── 047-payment-decline-reason.sql # payments.decline_reason (R44)
    ├── 048-stadium-wizard-fields.sql  # stadiums.landmark; stadium_amenities toilet, covered, bibs, drinking_water
    ├── 049-rounding-5000.sql         # payment_rounding_step 10 000 → 500 000 tiyin (5 000 so'm) where still default; column default too
    ├── 050-remainder-marks.sql       # bookings.remainder_mark / _marked_at / _marked_by; payments status 'cancelled' (voided cash)
    ├── 051-default-min-booking-amount.sql # platform_settings.default_min_booking_amount (5 000 000 tiyin)
    └── 052-game-team-payment-option.sql   # game_teams payment_option, join_price, remainder_price + remainder_mark / _marked_at / _marked_by
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
- 3 languages: uz (default), ru, en — stored in `user.language`. A user created on their
  first Telegram sign-in **always starts in `uz`**, whatever Telegram's `language_code`
  says; `PATCH /api/users/me` `language` is the only way to change it.
- **Profile** (`PATCH /api/users/me`, all optional, non-null applied): `name`,
  `language`, `firstName` / `lastName` (trimmed, 1–60 — "firstName must be between 1
  and 60 characters") and `phone` (`^\+998\d{9}$` — "phone must be in the format
  +998XXXXXXXXX"). `firstName` / `lastName` are **only ever set by the user**, never from
  Telegram (absent from `UserResponse` until then); setting either rewrites `name` as
  "first last". Phones are unique: another account's phone is **409 `PHONE_TAKEN`**
  "Phone is already used by another account". Audited `PROFILE_UPDATED` (PLAYER); the
  phone number itself is never logged, only `phoneChanged`.

### Stadiums

- 1 stadium = 1 pitch (format, surface_type on stadiums table)
- Statuses: `pending` → `approved` or `rejected` (turned down at review, only from
  pending); `approved` → `blocked` (taken off after approval) → `approved` again
  (unblocked). Rejecting or blocking needs a reason, kept in `stadiums.status_reason`
  and shown to the owner as `statusReason` on the owner endpoints; approving or setting
  back to pending clears it. Blocking cancels future bookings with full refunds;
  rejecting has none to cancel.
- Only `approved` (and active) stadiums are visible to players — rejected and blocked
  ones never are.
- **The player list** (`GET /api/stadiums`, public; `GET /api/favorites` is the same card):
  filters `q`, `format`, `maxPrice` (a player price) and `district` (one of the twelve,
  else 400 "Invalid district"), all optional — a JPA specification
  (`StadiumSpecifications.publicFilter`). Each `StadiumResponse` carries
  `coverPhotoUrl` (the lowest-position photo; absent when none), `averageRating` (absent
  with no reviews), `reviewCount`, `freeSlotsCount` (see "Availability Calculation") and for a
  signed-in caller `isFavorited`. Ratings,
  cover photos and favorites are loaded for the whole page in one grouped query each
  (`StadiumService.toPublicResponses`; `PublicStadiumListTest` counts the queries with 22
  stadiums). The detail (`GET /api/stadiums/{id}`) carries `averageRating` /
  `reviewCount` too.
- **Anyone signed in can add a stadium** (R42): `POST /api/owner/stadiums` and
  `GET /api/owner/stadiums` need only a token, not `isOwner`, so a player adds their first
  stadium and sees it (with `status` / `statusReason`) in their list. It starts `pending`;
  approval sets `is_owner`, and the user needs a fresh token (the flag is a JWT claim) for
  the rest of `/api/owner/**`, which stays owner-only.
- Owner adds stadium → admin reviews (`GET /api/admin/stadiums/{id}` works in any
  status) → approves or rejects.
- **Approval needs the owner's ATMOS contract signed** (`owner_legal_details`, see
  "Owner legal details"): otherwise `PUT /api/admin/stadiums/{id}/status` answers 400
  "The owner's ATMOS contract is not signed". **Then at least 3 photos** (checked after
  the contract): otherwise 400 "A stadium needs at least 3 photos to be approved"
  (`AdminService.MIN_PHOTOS_TO_APPROVE`; at most 10 as before). Both apply to every move
  into `approved` (from pending, rejected or blocked), not to re-sending `approved`. On
  approval: status → approved, user.is_owner → true.
- **What an owner sends** (`POST` / `PUT /api/owner/stadiums`), checked by **one helper,
  `util/StadiumRules`**, so create and update agree — every message is a 400 in the
  envelope. On create every required field must be there; on update only what is sent is
  checked (old stadiums that break a rule can still be edited elsewhere):
  - `name` trimmed 3–150 ("name is required" / "name must be between 3 and 150
    characters"); `address` trimmed 5–300 (same wording);
  - `district` **required on create** ("district is required"), see below;
  - `phone` **required on create** ("phone is required"), `^\+998\d{9}$` — "phone must
    be in the format +998XXXXXXXXX" (the profile's rule);
  - `description` ≤ 500 ("description must be at most 500 characters");
  - `landmark` ("Moʻljal", changeset 048) optional, trimmed, ≤ 150 ("landmark must be at
    most 150 characters"); blank is none, and on update clears it;
  - `latitude` / `longitude` **required on create** ("latitude and longitude are
    required"), on update both or neither ("Send both latitude and longitude"); inside
    Uzbekistan's box, lat 37.1–45.6, lon 55.9–73.2 — "Location must be inside Uzbekistan";
  - `pricePerHour` 1 000 000–500 000 000 tiyin — "pricePerHour must be between 10 000 and
    5 000 000 so'm" ("pricePerHour is required" on create);
  - `openTime` / `closeTime` on the hour ("openTime and closeTime must be on the hour")
    and `closeTime` after `openTime` — **closing after midnight is not supported**
    ("closeTime must be after openTime; closing after midnight is not supported"), since
    availability walks open → close within one day and bookings start on the hour. On
    update the pair that would result (sent value, else the current one) is checked.
  - **Not the owner's**: `minBookingAmount` / `cancellationHours` are the admin's
    (`PUT /api/admin/stadiums/{id}/booking-terms`), and photos come only through the
    upload endpoint. Those fields (and `clearMinBookingAmount`, `photos`) are gone from
    `CreateStadiumRequest` / `UpdateStadiumRequest`; an older client still sending them is
    **ignored, not refused** (unknown JSON properties are ignored).
- **Amenities** (`amenities` on create/update, all booleans, any omitted one false — the
  block replaces the old one when sent): `parking`, `lighting`, `showers`, `lockerRoom`,
  `wifi`, `cafe`, `equipmentRental`, `tribune`, and since changeset 048 `toilet`,
  `covered`, `bibs`, `drinkingWater`. `AmenitiesRequest` keeps its all-args constructor
  private so Jackson binds through the setters (otherwise a block missing a flag is a 400).
- `landmark` and `amenities` are in every stadium detail (player, owner, admin);
  `landmark` also in the list cards (`StadiumResponse`), absent when none.
- `district`: one of the twelve Tashkent districts — `Bektemir`, `Chilonzor`,
  `Mirobod`, `Mirzo Ulugʻbek` (with U+02BB, not an apostrophe), `Olmazor`, `Sergeli`,
  `Shayxontohur`, `Uchtepa`, `Yakkasaroy`, `Yangihayot`, `Yashnobod`, `Yunusobod`
  (`util/Districts`, CHECK `chk_stadiums_district`). Required when the owner creates a
  stadium, optional on update; anything else is 400 "Invalid district". Null on stadiums
  added before it existed.

### Bookings

- Source types:
  - `app` → player booked via Mini App
  - `manual` → owner added walk-in/phone booking (client_name, client_phone, is_walkin=true)
  - `game` → created automatically when a game is confirmed
- **A booking is exactly one hour** — one availability slot (`Booking.HOURS`), for player
  bookings, manual owner bookings, team and friends games and recurring bookings alike;
  `endsAt = startsAt + 1h`. The length fields that requests may still send
  (`durationHours` on `POST /api/bookings`; `duration` on manual bookings, series and
  games) are optional: absent or 1 is accepted, anything else is **400 "A booking is
  exactly one hour"** (`BookingWindowValidator.requireOneHour`). Rows stored before this
  rule may be longer and keep their length; an older series row that says more than one
  hour generates one-hour bookings. There is no multi-hour code path — don't add one.
  - `startsAt` must be on the hour in `Asia/Tashkent` (minutes/seconds/nanos = 0) so bookings line up with the hourly availability slots.
  - The hour (`startsAt` → `startsAt + 1h`) must fall within the stadium's `open_time`–`close_time` (Tashkent). `close_time` is treated as a same-day bound: a window where `close_time <= open_time` is unbookable (matches availability, which generates no slots for it), so bookings cannot cross midnight.
  - The hour must not fall in any `stadium_closure`.
  - Price = the slot's price (pricing rule by local weekday/time, falling back to `stadium.price_per_hour`, `BookingPriceCalculator.hourPrice`), with the best active promotion for that hour applied — identical to what the availability endpoint shows for the slot.
  - Violations throw `BadRequestException` → HTTP 400. An overlap rejected by the DB EXCLUDE constraint reports 409 "Slot is already booked".
- Max booking: 4 weeks ahead
- **The player's list** (`GET /api/bookings`, paged): every booking, latest start first;
  R29 `when=upcoming` — `startsAt` now or later, soonest first; `when=past` — `startsAt`
  before now, newest first; any other value 400 "Invalid when". Each item carries
  `paymentState` and `declineReason` (see "Why a card payment was refused").
- Hold: unpaid bookings set `expires_at = now + 10 minutes`, status = `pending`
- Expiry: `BookingExpiryJob` runs every minute, marks expired pending bookings as `expired`
- Status flow:
  - `pending` → `confirmed` (paid) → `completed`
  - `pending` → `expired` (unpaid timeout)
  - `confirmed` → `cancelled_by_user`
  - `confirmed` → `cancelled_by_owner` (always 100% refund)

#### Paying for a booking

`POST /api/bookings` takes a `cardId` (a saved card of the booking player) and a
`paymentOption`, and charges that card straight away:

| `paymentOption` | Charged | Allowed when |
|---|---|---|
| `FULL` | the player price **D**: the slot's player price | always |
| `MINIMUM` | the deposit **d** = `min(playerAmount(M), D)` | always (every stadium has a minimum, T1) |

All in player prices (see "Payments → The player price"). The rest, **r = D − d**, is
the same amount whether the player pays it in the app or the owner takes it in cash.

There are no other amounts. **M is the stadium's effective minimum** (T1,
`CardPricing.effectiveMinimum`): its own `min_booking_amount`, or
`platform_settings.default_min_booking_amount` (50 000 so'm) when it has none — or has one
not above the team-game fee, which is ignored at runtime, never rewritten. A stadium value,
when set, must be more than the team-game fee, at least 50 000 tiyin (500 so'm, the
smallest payment ATMOS takes) and at most the hour's own price. **Only an admin sets it**,
agreed with the owner by phone: `PUT /api/admin/stadiums/{id}/booking-terms` (see
"Cancellation Policy"; null = the platform default); the owner sees it read-only (R41's
`clearMinBookingAmount` is gone). There is no full-payment-only stadium any more: when the
deposit reaches the price, MINIMUM simply charges the whole of it.

- `bookings.payment_status` is `unpaid` / `partially_paid` / `paid`.
  `bookings.pricing_model` (changeset 040) says how the money is counted:
  - `player_price` — every booking from now on: `card_price` is D (the owner's own
    `price` on a manual booking) and `paid_amount` counts what the player paid, card
    or cash, so `remainingAmount = D − paid_amount`;
  - `net` — bookings made before the change keep their stored amounts and their old
    accounting (owner's price, net paid), and so do **game bookings**, whose teams'
    debts live on `game_teams`.
  Responses: see "Payments → API fields".
- A successful charge confirms the booking. A **declined** card leaves it
  `pending` with a `failed` payment row, so the usual hold timeout expires it;
  the create call still answers 200 with `paymentStatus: unpaid`, and the
  message says the payment failed.
- **Paying it again (R28):** `POST /api/bookings/{id}/pay` `{ cardId }` — the booking's
  own player, while it is `pending` and its hold has not run out, with no payment of it
  `processing` or arrived. The **same amount** as the first attempt (the deposit or the
  whole price), on the same path (`PaymentService.chargeForBooking`): success confirms it;
  a decline is still **200**, `pending`, the **same `expiresAt`** (the hold is never
  extended) and the new `declineReason`. The booking row is locked for the call, so two
  taps at once charge once. Errors: **409** `BOOKING_NOT_PENDING` "This booking is not
  awaiting payment", **409** `BOOKING_EXPIRED` "The booking's hold has expired; book the
  slot again", **409** `PAYMENT_IN_PROGRESS` "A payment for this booking is being
  processed"; 403 "This booking does not belong to you"; 404 "Booking not found" / "Card
  not found". Audited `BOOKING_PAYMENT_RETRIED` (PLAYER). Same messages as creation.
**Paying off the rest.** While a booking is `partially_paid` the remainder can be
settled two ways, and whichever lands first wins:

- `POST /api/bookings/{id}/pay-remainder` with a `cardId` — the player charges
  the whole remainder to a saved card, at any time, **before or after the game**.
  Paying after the game leaves the booking `completed`; it is never dragged back
  to `confirmed`.
- `POST /api/owner/bookings/{id}/cash` (R37, no body; "Naqd olindi") — the owner marks the
  same money as received in cash (it takes `remainingAmount`, not the full price), through
  `PaymentService.recordCashPayment`, and the booking gets `remainder_mark = cash_received`
  (E5); answers the owner's `BookingResponse`, "Cash recorded". See "Owner marks" below
  for who may and the 403 / 409 answers.

Both lock the booking row (`BookingRepository.findForUpdate`), so they settle one at a
time. Once `payment_status` is `paid`, the player's pay-remainder answers **400 "Already
paid"** and the owner's cash **409 "Already paid"**. The remainder is charged as a
`remainder` payment row, and a declined card leaves the booking `partially_paid` with a
`failed` row.

**Owner marks on the remainder (E5–E7, changeset 050).** `bookings.remainder_mark` is
`cash_received` ("Naqd olindi"), `not_paid` ("Toʻlanmadi") or null, with
`remainder_marked_at` / `remainder_marked_by`:

- `POST /api/owner/bookings/{id}/cash` sets `cash_received` (and records the cash, above).
- `POST /api/owner/bookings/{id}/not-paid` sets `not_paid`: no charge, no penalty, the
  remainder stands. "Marked as not paid"; marking again changes nothing. The player can
  still pay it in the app — that **clears** `not_paid` (`applySuccessfulBookingPayment`) —
  and the owner can still take cash later, which **replaces** it.
- `DELETE /api/owner/bookings/{id}/remainder-mark` undoes it, "Mark undone": allowed until
  the **end of the Tashkent day** of `remainder_marked_at`
  (`BookingService.markUndoableUntil`, shown as `remainderMarkUndoableUntil`); later
  **409 "Only an admin can change this mark"**. Undoing `cash_received` voids the cash row
  (`PaymentService.voidLatestCashPayment`: status **`cancelled`**, never a platform
  refund) and recomputes `paid_amount` / `payment_status` (the booking's own status stays);
  undoing `not_paid` only clears the mark. No mark: **409 "This booking has no remainder
  mark"**.
- `DELETE /api/admin/bookings/{id}/remainder-mark` — an admin, any time, same effect;
  answers the `AdminBookingResponse`.
- Both marks apply to a `confirmed`/`completed` booking that is `partially_paid`, or a
  manual (walk-in) booking still `unpaid`. Otherwise **409**: a pending, cancelled, expired
  or game booking (its teams' remainders go through `POST /api/games/{id}/cash`) — "This
  booking has no part payment to complete"; nothing left (paid in full) — "Already paid".
  These were 400 on the cash endpoint before. Cash also answers 409 "A payment for this
  booking is being processed" while a card payment is `processing` / `capture_pending`.
  Not the stadium's owner **403 "You do not own this stadium"**; 404 "Booking not found".
- **A walk-in's payment** (`POST /api/owner/stadiums/{id}/bookings`): optional `price` (tiyin,
  1 … `PRICE_MAX`; absent = the owner's price for that hour, the same figure
  `GET /api/owner/stadiums/{id}/bookings/suggested-price?startsAt=` answers for the form) and
  `paymentStatus` `unpaid` (the default: players pay after the game) or `paid`. `clientPhone`
  is `+998XXXXXXXXX`. Added as `paid`, it records the cash row and the `cash_received` mark, so
  the owner can undo a slip the same day like any other mark. Walk-ins never touch the platform
  or a card; they count in the owner's `paidInCash` / `outstanding` figures.
- Audited: `CASH_RECEIVED` (with `remainderMark`), `REMAINDER_NOT_PAID` (OWNER),
  `REMAINDER_MARK_UNDONE` (OWNER, or ADMIN for the admin's undo; `remainderMark`,
  `paidAmount`, `paymentStatus`, `cashPaymentStatus`). Paying the rest in the app records
  the cleared mark in `BOOKING_REMAINDER_PAID`.
- A voided cash row counts nowhere: not in `paidInCash`, `cashReceived`, `ownerReceives`,
  the owner stats' `…PaidInCash`, nor revenue; the booking is back on the unpaid list.

- **`PaymentService.applySuccessfulBookingPayment` is the only path from "money
  arrived" to "booking confirmed"** — the synchronous charge uses it today and a
  provider webhook will use the same method. If the hold already expired when
  the money lands, it confirms the booking only when nothing else has taken the
  slot; otherwise it refunds that payment in full and notifies the player. The
  EXCLUDE constraint is the final arbiter and a violation is caught and refunded
  there too, so **an overlap error never travels back to the payment provider**.
  This is about an *ordinary* booking. A **team game** books its own slot in
  `TeamGameService.join`, which sets `paid_amount` from the teams' advances and
  confirms the booking itself, because the money arrived before the booking
  existed and belongs to the teams rather than to the booking.

### Cancellation Policy (configurable per stadium)

Cancellation is **all or nothing** — there are no partial refunds, because ATMOS
cannot refund part of a transaction. Each stadium has one setting,
`cancellation_hours` (default 24):

```java
deadline = booking.startsAt - stadium.cancellationHours;

if (now <= deadline) → every card payment of the booking refunded in full
else                 → the booking is still cancelled, but nothing is refunded

// Owner cancels → ALWAYS every card payment back, whatever the time left
// Admin blocks the stadium → same as an owner cancellation
// Cash is NEVER refunded through the platform; the owner settles that directly
```

- **One method does every refund**: `PaymentService.refundInFull(payment, reason)`,
  driven for a whole booking by `refundAllCardPayments(bookingId, reason)`. There
  is no method that refunds an arbitrary amount, so a partial refund cannot be
  written by accident. A failed row, a cash row and an already-refunded row are
  all skipped.
- **A refund ATMOS declines never undoes the cancellation.** The refund row is still
  written, `status = failed` with the provider's message and code in `failure_reason`;
  the payment it was to give back becomes `refund_failed` (the money is still owed);
  audit `REFUND_FAILED` (SYSTEM). An admin then retries it or marks it refunded by hand
  (see "Admin panel API → Failed refunds"). A lost answer is different: the row stays
  `return_pending` for the reconcile job. A **hold** release ATMOS refuses is not a failed
  refund (no money left the card, and the hold lapses on its own): it is logged and left
  `return_pending` for the job.
- Booking responses carry **`cancellationDeadline`** and
  **`refundIfCancelledNow`** (tiyin). Both come from `CancellationPolicy` and the
  same refundable payments that a real cancellation uses, so the figure a player
  is shown is the figure they get. `BookingResponseFactory` fills them in.
- `cancellationPolicy` on a booking or stadium response is just
  `{ "cancellationHours": 24 }`.
- The default of **24 lives in exactly two places that agree**: the
  `stadiums.cancellation_hours` column default and the `Stadium` entity field.
  There is no `app.cancellation.*` config — don't reintroduce one (nor a
  `platform_settings` column for it). The minimum booking amount's platform default is
  `platform_settings.default_min_booking_amount` (T1, changeset 051).
- **Who sets the booking terms**: the owner never does (since the add-stadium rework).
  `cancellation_hours` and `min_booking_amount` are agreed with the platform by phone and
  set by an admin: `PUT /api/admin/stadiums/{id}/booking-terms`
  `{ minBookingAmount: Long|null, cancellationHours: Integer }` — the whole terms each
  time: `minBookingAmount` null (or absent) = **the platform default** (T2), else ≥ 50 000
  tiyin ("minBookingAmount must be at least 50000 tiyin (500 so'm)"), ≤ `pricePerHour`
  ("minBookingAmount must not be more than pricePerHour") and more than the team-game fee
  ("minBookingAmount must be more than the team game platform fee (25 000 so'm)"); the
  responses' `playerMinBookingAmount` is the effective minimum's; `cancellationHours` required
  ("cancellationHours is required"), 0–168 ("cancellationHours must be between 0 and
  168"). Any stadium status; the row is locked. Audited `STADIUM_BOOKING_TERMS_CHANGED`
  (ADMIN, `minBookingAmount` / `cancellationHours` old → new, nothing when unchanged);
  answers the admin stadium detail, "Booking terms updated"
  (`AdminStadiumService.updateBookingTerms`, `AdminStadiumBookingTermsTest`).

Players MUST see the stadium's cancellation policy before confirming a booking.

### Recurring Bookings (booking_series)

- Player says "repeat every Thursday 20:00" — one hour each time, like every booking
- Creates a `booking_series` row + bookings for the next 2 weeks
- `BookingSeriesJob` runs daily, generates next week's booking when the window needs extending
- Each generated booking gets the CURRENT price (price changes apply to future bookings)
- Cancelling one booking: only that row cancelled, series stays active
- Cancelling the series: `is_active = false`, all future bookings cancelled

### Teams

A team has one leader (who also holds a `team_members` row, role `leader`) and members;
`TeamService`. There is no team size limit. The leader adds a member by user id
(`POST /api/teams/{id}/members`, already a member → 400), or shares an **invite link**
(R32, changeset 043 `teams.invite_code`, unique, null until first asked for):

| Endpoint | Who | Answer |
|---|---|---|
| `GET /api/teams/{id}/invite` | leader | `{ code }` — created on first request: 128 random bits, base64url, 22 chars |
| `POST /api/teams/{id}/invite` | leader | `{ code }`, "Invite link renewed" — a new code; links with the old one stop working |
| `GET /api/teams/invite/{code}` | anyone signed in | `{ team: TeamResponse, isMember }`; unknown code 404 "Invite not found" |
| `POST /api/teams/join` `{ code }` (`@NotBlank`) | anyone signed in | `TeamDetailResponse`, "Joined team" — added as a `member` through the same code path as add-member; 404 "Invite not found"; already a member **409** "User is already a member of this team" |

Non-leader on the invite endpoints → 403 "Only the team leader can perform this action";
unknown team → 404 "Team not found". The code never appears in a team response. The
add-member endpoint keeps its 400 for "already a member"; only the join answers 409.
Team changes are not audited (no team actions exist in `AuditAction`).

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

All of this lives in `service/TeamGameService.java`, not in `GameService`, which
only keeps the shared create/join/cancel entry points and delegates when
`type = team_vs_team`. `creator_share_pct` is gone — the column was dropped in
changeset 025 and there is no percentage split any more.

*States.* Three of them, and the first is persisted as the existing
`games.status = 'open'` — the value was deliberately **not** renamed, because
casual games share the column:

| State | `games.status` | The slot |
|---|---|---|
| WAITING | `open` | **not booked** — free for everyone, the one team's money only on hold |
| CONFIRMED | `confirmed` | a `source = game` booking exists |
| CANCELLED | `cancelled_slot_taken` / `cancelled_by_creator` / `cancelled_not_filled` | free |

A CONFIRMED game can go **back to WAITING** when one team leaves. Its booking is
cancelled and a new one is created when the next opponent joins.

*Money: minimum or full, per team (T4–T6, `util/TeamPaymentQuote`).* For pitch price `P`
(the game's `total_price`, owner's terms), the stadium's **effective** minimum `M` (its own,
or the platform default — see "Paying for a booking") and the team fee `F`
(`platform_settings.team_game_platform_fee`), all tiyin, each team chooses on creating or
joining (`paymentOption`, `MINIMUM` when absent):

| Option | Net | Player pays now | Platform | Owner part (net) | Left to pay (player) |
|---|---|---|---|---|---|
| `minimum` | `M` | `playerAmount(M)` | `F` | `M − F` | `playerAmount(P/2 + F) − paid` |
| `full` | `P/2 + F` | `playerAmount(P/2 + F)` | `F` | `P/2` | 0 |

**Example** (fee 25 000, card fee 1.5 %, step 5 000 so'm; P = 200 000, M = 50 000): minimum
**55 000** now and **75 000** later (130 000 − 55 000), platform 25 000, owner part 25 000;
full **130 000**, platform 25 000, owner part 100 000. In tiyin 5 500 000 / 7 500 000 /
13 000 000.

- The odd tiyin of an odd `P` goes to side **B** (`P/2` is `P − P/2` there).
- `minimum` is offered only when `F < M < P/2 + F` and its player amount is below the full
  one; otherwise only `full` is, and a `MINIMUM` request is simply taken as `FULL`.
- The remainder is **never rounded on its own**: it is the full player amount less what was
  paid, the same in the app and in cash, and **100 % the owner's** (`CardFeeSplit.toOwner`).
- Every joining payment is split `CardFeeSplit.withPlatformFee(paid, F)`: the platform's
  split is `ceil(F × 10000 / (10000 − card_fee_bps))`, so after ATMOS it nets **exactly F**;
  everything else, rounding included, is the owner's — so with both teams on `full` the owner
  nets at least `P` (`TeamPaymentQuoteTest`, a property test over 20 000 prices, minimums,
  fees, steps and card fees). The fee is the platform's **only** money in a team game.
- Stored per team on `game_teams` (changeset 052): `payment_option`, `join_price` (held or
  charged), `remainder_price` (left), and in the owner's terms `fee_amount` = F,
  `advance_amount` = net − F, `remainder_amount` = full net − net. `CardPricing.teamJoinPrice` /
  `teamRemainderPrice(GameTeam)` read them.
- **Rows from before changeset 052** (`payment_option` NULL) keep their stored amounts and the
  old rule (`util/TeamGameSplit`: each paid `playerAmount(fee + base/2)`, remainder
  `playerAmount((P − base)/2)`); nothing about them is recomputed.
- **Quotes** (T7), player amounts only, `TeamGameQuoteResponse { options: [{ option:
  "minimum", amount, remainderAmount }, { option: "full", amount, remainderAmount: 0 }],
  cancellationDeadline }` — `minimum` listed only when offered: `GET
  /api/stadiums/{id}/team-game-quote?startsAt=` (public, creating, side A; 400 for an hour
  that cannot be booked — past, off the hour, outside opening hours, closed, taken; 404
  unknown stadium) and `GET /api/games/{id}/join-quote` (the free side; 404 unknown; 400 not
  a team game / not waiting / past the 3-hour point).
See "Payments → Team game money → ATMOS operations" for holds, captures and reversals.

*The two deadlines.*
- **the deadline** — `starts_at − stadium.cancellation_hours` (default 24).
- **the 3-hour point** — `starts_at − app.game.opponent-deadline-hours`
  (default 3). After it nobody may join and a game still without an opponent is
  over. **team_vs_team only** — casual games still fill right up to kick-off.

*Creating (WAITING).* The creating leader picks a saved card and an option; the chosen
amount goes on **HOLD** (`payments.status = held`, type `team_game`). No slot is booked. A
declined card means no game at all.

*Joining (→ CONFIRMED).* Order matters, every failure leaves nobody paying for a game
that will not happen, and **each step commits on its own** (`TeamGameService.join`,
deliberately not one transaction — `GameService.joinGame` is not `@Transactional`), so a
crash or an unclear answer half-way leaves a record the reconcile job finishes, never
money that moved with no row for it:
1. after the 3-hour point → 400 `O'yin boshlanishiga 3 soatdan kam qoldi, qo'shilib bo'lmaydi`;
2. **step 0, committed before any provider call**: the joining team as
   **`joining`** (changeset 046; it holds its side — the partial unique index on
   `(game_id, side)` covers `active` and `joining` — so a second opponent is rejected
   before any card is touched), the slot as a pending `source = game` booking, and the
   joiner's `processing` payment row. If the EXCLUDE constraint rejects the slot nothing
   is committed and nobody is charged;
3. charge the joining team: created (its transaction ids committed), then confirmed. A
   decline drops the join (team `cancelled`, booking deleted) and the game goes on
   waiting; no answer drops it the same way and the reconcile job gives the money back
   if it was taken; an unreachable provider → 502;
4. capture the waiting team's hold, last: the hold is marked `capture_pending`
   (committed), then captured;
   - **captured** → the team becomes `active`, the booking and game confirmed, the
     leader's participant row, notifications and audit written;
   - **refused, with any code** (lapsed, cancelled, or any other refusal) → **undone**:
     the decision is committed first (game `cancelled_slot_taken`, audit reason naming
     the provider's code, every team out, booking deleted, every payment to give back
     `return_pending`), then the joiner is reversed and the hold released, each in its
     own step; both teams notified; 400 "The other team's payment could not be taken, so
     the game is cancelled; your payment is being returned in full";
   - **no clear answer** (timeout, provider unavailable) → left `capture_pending` and
     answered **202 `PAYMENT_PENDING`**; the reconcile job finishes it.

`TeamGameService.resumeJoin` drives a join from wherever it stands, and is what the
reconcile job runs (`GameService.resumeStuckJoins`) for every team still `joining` after
five minutes, once that run's payments are settled: the joiner's charge unclear → wait;
not charged → dropped; the game ended meanwhile (its 3-hour point, its leader, a booking)
→ undone, the joiner refunded; the capture `captured` → confirmed; `released` or refused
→ undone. Every step re-reads and locks its rows, so running it twice is safe.

The joining team takes whichever side is **free**, not always B: when the
creating team walks out of a confirmed game the team left behind is on side B,
so the next opponent takes A. **The server decides the side**, never the client:
`POST /api/games/{id}/join` on a team game takes `teamId` + `cardId` from that team's
**leader** only (anyone else, or no `teamId` → 403 "Only the joining team's leader can
join a team game, with their team and a card"; no card → 400), and the leader always
pays. A sent `side` is optional; `"b"` is still accepted from older clients, any other
side that is not the free one → 400 "The opposing team joins as side b". Confirming also clears any **other** team still
waiting on that hour, the same rule an ordinary booking follows — otherwise
their money would sit on hold for a pitch that is already gone.

The game's booking (`TeamGameService.refreshBookingPayment`): when **every** team in the
game chose an option, it counts in player terms — `pricing_model = player_price`,
`card_price` = what both teams pay altogether (each `join_price + remainder_price`) and
`paid_amount` = what they paid of it — so its `remainingAmount` (owner schedule, unpaid list,
stats' outstanding) is exactly the teams' unpaid rests; `paid` once nothing is left (both
on `full`, or both rests settled). Otherwise (a team from before changeset 052) it keeps the
old `net` accounting: the sum of the teams' advances plus any settled remainder.
Its **money fields** come from the teams' payments, since none sit on the booking
(`BookingResponseFactory.moneyPayments` → `PaymentRepository.findTeamPaymentsOfGameBooking`:
the payments of the game's `active` teams, the ones `paidAmount` counts), in the owner and admin
views alike: `paidInApp` = their card money that arrived (`paid` / `captured` / `completed`; a
hold counts only once captured — on a confirmed game both joining payments are, so it agrees
with `paidAmount`), `paidInCash` and the admin's `cashReceived` = their cash not voided,
`ownerReceives` = owner splits after ATMOS plus that cash, `atmosFee` ATMOS's part.
`paymentState`, `declineReason` and `refundIfCancelledNow` stay the booking's own, and its
`remainderMark` stays absent (marks are per team). Only `player_price` game bookings with team
payments; a `net` (legacy) game booking keeps its own (zero) figures. `TeamGameBookingMoneyTest`.

*Everything back / nothing back.* "Everything back" releases a `held` payment
(no refund row — no money ever moved) and refunds a settled one in full;
"nothing back" captures a `held` payment and keeps the rest. One pair of methods:
`PaymentService.giveBackAllTeamPayments` / `keepAllTeamPayments`, previewed
without moving anything by `refundableTeamTotal`.

*A WAITING game ends without an opponent* — the waiting team gets everything back
in all three cases:

| Trigger | Status |
|---|---|
| someone books the slot (app or manual) | `cancelled_slot_taken` |
| the 3-hour point passes (`GameTimeoutJob`) | `cancelled_not_filled` |
| its leader cancels **before the deadline** | `cancelled_by_creator` |
| its leader cancels **after the deadline** | `cancelled_by_creator`, **nothing back** |
| its leader cancels **past the 3-hour point** | `cancelled_not_filled`, everything back — the job would have refunded them within the minute, so racing it must not cost them |

*A CONFIRMED game: either leader may cancel* (not just the creator), and the
booking is released as **`cancelled_opponent_left`** so the owner's schedule
says why the pitch is free again rather than blaming a player or the owner.

**Nothing is cancellable once kick-off has passed**, waiting or confirmed. The
pitch was there to be used, and nothing sets `games.status = 'completed'` yet, so
without that guard a played game stays `confirmed` and the losing leader could
reverse its booking and have the opponent refunded for a game they actually got.

| When | The canceller | The other team | The game |
|---|---|---|---|
| before the deadline | everything back, remainder included | keeps its payments, stays in | back to **WAITING** |
| after the deadline, before the 3-hour point | nothing back | keeps its payments, stays in | back to **WAITING** |
| past the 3-hour point | nothing back | **everything back** | `cancelled_not_filled` |

The bands are evaluated with the 3-hour rule **first**, so they still behave on a
stadium whose `cancellation_hours` is under 3, where the two would otherwise
overlap and contradict each other.

The owner cancelling the game's booking (`BookingService.cancelByOwner`), or an
admin blocking the stadium, goes through `GameService.cancelGameForBooking` →
`TeamGameService.cancelForBookingCancelled`: **both** teams get everything back
whatever the window says, and the game ends. Refunding the booking's own payments
would hand back nothing, because a team game's money never sits on the booking.

*Remainder, per team.* Two separate debts. Each leader settles their own from a
saved card at `POST /api/games/{id}/pay-remainder` (before or after the game; 400
"Already paid" when the team owes nothing — paid in full, or settled). The owner takes
either team's in cash at `POST /api/games/{id}/cash` `{"side":"a"}`. Whichever lands first
wins. The booking reaches `paid` only once **both** teams currently in the game have
settled.

*Owner marks on a team's remainder (T10)* — the booking rules ("Paying for a booking →
Owner marks") on `game_teams.remainder_mark` / `_marked_at` / `_marked_by`:
- `POST /api/games/{id}/cash {side}` records the cash (the team's `remainder_price`) **and**
  sets `cash_received` (replacing `not_paid`); "Cash recorded".
- `POST /api/games/{id}/not-paid {side}` sets `not_paid` — no charge, no penalty; again changes
  nothing; "Marked as not paid". Paying the rest in the app **clears** it (`settleRemainder`,
  also after a late answer); cash replaces it.
- `DELETE /api/games/{id}/remainder-mark?side=` — the owner's undo until the end of the
  Tashkent day of the mark (`BookingService.markUndoableUntil`), later **409 "Only an admin
  can change this mark"**; `DELETE /api/admin/games/{id}/remainder-mark?side=` — an admin, any
  time (answers the admin game detail). Undoing `cash_received` voids that team's latest cash
  row (`PaymentService.voidLatestTeamCashPayment`: `cancelled`) and the rest is owed again;
  "Mark undone". No mark **409 "This team has no remainder mark"**.
- Cash and not-paid: **403** "You do not own this stadium"; **409** "This game is cancelled",
  "This game is not confirmed yet" (waiting), **"Already paid"** (nothing left — this was 400
  on the cash call before), "A payment for this team is being processed"; 404 "No team is on
  side a".
- Audited on the game: `CASH_RECEIVED` (`teamX.remainderStatus`, `teamX.remainderMark`,
  `teamX.cashAmount`), `REMAINDER_NOT_PAID`, `REMAINDER_MARK_UNDONE` (OWNER / ADMIN;
  `teamX.remainderMark`, `teamX.remainderStatus`, `teamX.cashPaymentStatus`),
  `GAME_REMAINDER_PAID` (with the cleared mark).

*What a team sees.* `GameDetailResponse.teams` carries, per team, in player prices:
`paymentOption` (`minimum` / `full`; absent on older teams), `price` (paid or held on
joining), `remainderAmount` (its rest, the same in the app or in cash), `remainderStatus`,
`paidAmount` (held or arrived, card or cash, a voided cash row excluded), `remainingAmount`
(`remainderAmount` while unpaid, else 0), `remainderMark` / `remainderMarkedAt`, for the
stadium's owner `remainderMarkUndoableUntil`, `paidInApp`, `heldAmount` (tiyin on hold on the
team's card now: a hold `held` or `capture_pending` that has not lapsed,
`PaymentService.heldTeamTotal`; 0 otherwise), `cancellationDeadline` and
`refundIfCancelledNow` (tiyin, 0 when nothing would come back) — no fee or advance. The
admin's game detail adds `platformFee`, `ownerPart` (the owner's net part of the joining
payment), `ownerAdvance`, `ownerRemainder`, `ownerReceives` and `atmosFee`.
`refundIfCancelledNow` is computed by the same code that performs the
cancellation, so the figure shown is the figure paid; the cancellation bands are unchanged
and apply to what each team actually paid (T11). Note the asymmetry it
answers: past the 3-hour point a team's own figure is 0 because cancelling
forfeits, yet that same team is made whole if its *opponent* cancels.

*Finding opponents.* `GET /api/games/challenges` (filters `date`, `format`,
`stadiumId`; paged) lists WAITING team games before their 3-hour point, excluding
any the caller's own team is waiting on (membership and leadership both count), and
any another team is half-way through joining (R45: a `joining` team, e.g. after 202
`PAYMENT_PENDING` — left out for everyone, the joining captain too; back on the board
if that join fails). `GameDetailResponse.joinPending` (team games only) is true meanwhile.
Each item carries `amountToPayNow` = the free side's **minimum** option (T8; the full one
when no minimum is offered), `remainderAmount` = what is left after it, `fullAmount` = the
full option, and `totalPrice` = what both teams pay altogether (a team in counts what it
fixed, a free side in full), plus `stadiumDistrict` and `stadiumPhotoUrl` (E9: the cover photo, absent
when none; the page's covers in one grouped query, `GameRepository.findStadiumPhotoUrls`).
**Where the game is played:** each board item and every `GameDetailResponse` (any game type,
anyone who opens it, in every status) carry `location` — `StadiumLocationResponse` from
`StadiumMapper.toLocationResponse`: `id`, `name`, `address`, `district`, `landmark` (Moʻljal),
`latitude`, `longitude`, `coverPhotoUrl`, `format` (absent fields omitted). The board fetches
the stadiums with its page (`@EntityGraph` on `findChallenges`), so no stadium loads per card
(`TeamGameLocationTest`).
A team game
stays `is_public = false`; this endpoint queries by type and status instead.
Availability and the owner's schedule mark a slot where a team is looking for an
opponent — **only** team_vs_team, never casual.

*Chat* is open exactly while the game is CONFIRMED, for reading as well as
sending. A leader whose team joined partway through sees only messages sent
after they joined. A team that **left** loses the chat entirely: its players are
marked `cancelled` with it, so a departed leader cannot read what the teams
still playing say afterwards. An invited member leads no team, so they keep
their side's whole history.

*Members.* Leaders still invite team members via the link, and members do **not**
pay for the pitch — but a member cannot join a team game through
`POST /api/games/{id}/join` (403); that endpoint is the paying leader's only.

**Common game rules:**
- `GameTimeoutJob` runs two cutoffs: team games whose **3-hour point** has
  passed, and every other type whose `starts_at` has passed
- When a regular booking takes the slot, find any waiting game on that
  stadium/time → cancel it `cancelled_slot_taken` and give everyone their money
  back (team games through `TeamGameService`, casual through participants)
- Game statuses: `open` → `confirmed` → `completed`
- Cancel statuses: `cancelled_slot_taken`, `cancelled_by_creator`, `cancelled_not_filled`
- `POST /api/games/{id}/pay` is the **casual** payment and refuses a team game (a team
  pays when creating or joining). With `cardId` it charges that saved card through
  ATMOS (`playerAmount(share + service fee)`; all the owner's unless
  `casual_game_service_fee > 0`, which then works like the team fee); without it, the
  legacy hosted checkout.

### Platform Settings

Platform-wide money rules live in the single-row `platform_settings` table, not in
config. Admins read and change them at `GET` / `PUT /api/admin/settings`.

| Setting | Default | Meaning |
|---|---|---|
| `team_game_platform_fee` | 2 500 000 tiyin (25 000 so'm) | Charged per team on a team game — the platform's only money |
| `casual_game_service_fee` | 0 | Charged per player on a casual game |
| `card_fee_bps` | 150 (1.5%) | The fee ATMOS takes; player prices are grossed up by it (0–1000) |
| `payment_rounding_step` | 500 000 tiyin (5 000 so'm; changeset 049 — 10 000 before) | Player prices are rounded up to a multiple of it (1–1 000 000) |
| `default_min_booking_amount` | 5 000 000 tiyin (50 000 so'm; changeset 051) | The minimum booking amount of every stadium without its own (T1); API `defaultMinBookingAmount` |

- There is **no booking commission**: the platform takes nothing from ordinary
  bookings. `booking_commission_percent` was dropped in changeset 040; old payments
  keep their stored `commission_percent`.
- The row is seeded by changeset 021; `singleton` is UNIQUE and CHECKed true, so a
  second row is impossible.
- Validation: fees not negative, card fee 0–1000 bps, rounding step 1–1 000 000 tiyin;
  the default minimum must stay **above the team fee** (T2), checked on the values the row
  would have: "defaultMinBookingAmount must be more than the team game platform fee
  (25 000 so'm)" / "teamGamePlatformFee must be less than the default minimum booking amount
  (50 000 so'm)". A rejected value writes nothing.
- Every change writes one `SETTINGS_CHANGED` audit entry listing each changed
  field with its old and new value (see "Audit log").

### Payments

#### The player price

**The owner enters their own price; players see ONE price.** The owner's price (`N`,
`stadiums.price_per_hour`, pricing rules, `min_booking_amount`) is stored and edited as
the owner typed it. Players see and pay one price per amount — never the words or
fields "card fee", "commission", "net" or any breakdown. **One helper converts**,
integer math only (`PriceCalculator.playerAmount`, applied with the settings in force by
`CardPricing.playerAmount`); nothing else calculates it:

```
D = playerAmount(N) = roundUp( ceil(N * 10000 / (10000 - card_fee_bps)), payment_rounding_step )

step 5 000 so'm:  150 000 → 155 000;  160 000 → 165 000;  197 000 → 200 000;  200 000 → 205 000
step   100 so'm:  150 000 → 152 300
```

**The platform takes nothing from ordinary bookings.** Every booking card payment —
full, deposit or remainder — is 100% the owner's split (`CardFeeSplit.toOwner`, platform
share 0). ATMOS takes its fee from it; the rounding remainder is the owner's. Example:
owner price 150 000 → the player pays **155 000** (step 5 000) → the owner receives
**152 675** (155 000 less 1.5%).

| Payment | Player pays | Split |
|---|---|---|
| Booking, in full | **D** = the sum of each hour's `playerAmount`, so the slot prices on screen add up exactly; stored as `bookings.card_price` | all the owner's |
| Booking, deposit | **d** = `min(playerAmount(M), D)`, M the effective minimum (50 000 → 55 000 at a 5 000 step) | all the owner's |
| Booking remainder | **r = D − d**, the same amount in the app or in cash | all the owner's |
| Manual (walk-in) booking | the owner's own price, no conversion — cash between them | — |
| Team game, per team, on joining | `minimum`: `playerAmount(M)`; `full`: `playerAmount(P/2 + fee)` (older teams: `playerAmount(advance + fee)`) | platform `ceil(fee × 10000 / (10000 − bps))` — nets exactly the fee; the rest, rounding included, the owner's |
| Team remainder | `playerAmount(P/2 + fee) − paid`, the same in the app or in cash (older teams: `playerAmount(remainder)`) | all the owner's |
| Friends game, per player | `playerAmount(pitch share + service fee)` | all the owner's; a service fee > 0 is split like the team fee |

Cash goes to the owner in full. A full refund returns everything the player paid by
card. Existing bookings and payments keep their stored amounts (`pricing_model = net`).

ATMOS takes its rate on **each split**, rounded per split: what a recipient keeps of a
split `g` is `PriceCalculator.netAfterFee(g) = floor(g × (10000 − bps) / 10000)`, the
inverse of `grossUp`. So the platform's team-fee split nets exactly the fee; with that
rounding the owner's share of a team payment can come out at most 1 tiyin under the
advance (`PlayerPriceRuleTest`).

#### API fields

Every amount a player sees is the final amount they pay.

- **Player** (public stadium list/detail, availability slots, public pricing rules, the
  player's own bookings, games and payments): `pricePerHour` (a slot's `originalPrice`
  too) and a stadium's `minBookingAmount` are player prices; a booking's `price` is D,
  `paidAmount` what was paid of it, `remainingAmount` what is left;
  `refundIfCancelledNow` is everything paid by card; `paidInCash` the cash the owner took
  that still counts, and `remainderMark` the owner's mark (`cash_received` / `not_paid`,
  absent when none). A free availability slot carries
  `depositAmount` (`CardPricing.deposit` of the effective minimum, exactly what a MINIMUM
  booking charges) and `remainderAmount` (`pricePerHour − depositAmount`), absent only when
  the deposit is the whole price (T3). Games: `totalPrice` is what the
  players pay altogether, a friends game's `price` one share, a team's `price` its
  joining payment and `remainderAmount` its remainder; the challenge board's
  `amountToPayNow` / `remainderAmount` likewise. No breakdown field exists in these
  views; `PriceFieldVisibilityTest` fails on one.
- **Owner**: `pricePerHour` / `minBookingAmount` are the owner's own (what they edit),
  with `playerPrice` / `playerMinBookingAmount` alongside. A booking shows `price` (what
  the player pays), `paidAmount` and `remainingAmount` (the remainder to collect in cash)
  **exactly as the player sees them**, plus `ownerPrice`, `paidInApp` and `ownerReceives`
  (card splits after ATMOS, plus cash), `paidInCash`, `remainderMark`, `remainderMarkedAt`
  and `remainderMarkUndoableUntil` (the end of the mark's Tashkent day; the admin view
  has the mark fields but not this one).
- In the player, owner and admin booking views alike, **`paidAmount + remainingAmount =
  price` for every booking**: a `net` booking (made before changeset 040, and a game
  booking with a team from before changeset 052) keeps its stored owner's-terms amounts, and `BookingResponseFactory` converts
  them the one way (`CardPricing.playerPrice` / `remainingForPlayer`) for every view.
- **Admin**: the owner's view plus `ownerReceives` and `atmosFee` on stadiums, pricing
  rules and bookings; a team adds `platformFee`, `ownerPart`, `ownerAdvance`, `ownerRemainder`,
  `ownerReceives`, `atmosFee`; a payment shows `ownerAmount`, `platformAmount`,
  `atmosFee` (`card_fee_expected`), `providerCommission` and what each side keeps after ATMOS,
  `ownerNet` / `platformNet` (`util/PaymentNets`, read from the stored row;
  `ownerNet + platformNet + atmosFee = amount`, and `platformNet` is exactly the team fee).
- The public search's `maxPrice` is a player price, converted to the largest fitting
  owner price.

#### Why a card payment was refused (R44)

A charge or hold the provider refuses says why, in the player's terms, mapped from the
provider's code in exactly one place, `provider/DeclineReason.of`:

| Reason | ATMOS | Fake card ending |
|---|---|---|
| `INSUFFICIENT_FUNDS` | `STPIMS-ERR-057`, `-112` | `0051` (or the balance spent) |
| `CARD_EXPIRED` | `STPIMS-ERR-067` | `0054` |
| `CARD_DECLINED` | every other refusal — the fallback; an unknown code is logged (the code only) | `0002` |

It is stored on the failed row (`payments.decline_reason`, changeset 047; null when a
payment failed without a refusal, e.g. never acknowledged, or the provider was
unreachable) and shown as:
- the **`code`** of the 400 declines: `POST /api/games` (team game), `POST /api/games/{id}/join`,
  `POST /api/games/{id}/pay-remainder`, `POST /api/games/{id}/pay?cardId=` — `CardDeclinedException`;
- **`declineReason`** on `BookingResponse` (every view): why the booking's latest card
  payment (in full, deposit or remainder) was refused — a declined booking or remainder
  answers 200, so this is where the reason is; absent once a later payment went through;
- **`paymentState`** on `BookingResponse` (every view, always present), from the same
  latest card payment, so **`declined` ⇔ `declineReason` present**: `paid` (its money
  arrived, even if given back since), `processing` (no answer yet), `declined` (refused —
  see the reason), `failed` (failed without a refusal, e.g. never acknowledged), `none`
  (no card payment: cash, manual and game bookings). `BookingResponseFactory.paymentStateOf`;
- **`declineReason`** on `PaymentResponse` (`GET /api/payments/my`) and
  `AdminPaymentResponse`, on a refused row. Never the provider's own code or text.

#### The provider

Every flow goes through `PaymentProvider` (`provider/`), shaped around the ATMOS
operations. Amounts are tiyin; **there is no partial refund anywhere**.

| Operation | ATMOS |
|---|---|
| `bindCardInit(cardNumber, expiry)` → bindingId, maskedPhone | `POST /partner/bind-card/init` |
| `bindCardConfirm(bindingId, otp)` → cardToken, cardId, maskedPan, expiry | `POST /partner/bind-card/confirm` |
| `removeCard(cardId, cardToken)` | `POST /partner/remove-card` |
| `charge(splits, token \| number+expiry, account)` → transaction ids, needs OTP without a token | `POST /merchant/bulk/pay/create` + `pre-apply` |
| `confirmCharge(transactionIds, otp)` → successTransIds, commission, ofdUrl | `POST /merchant/bulk/pay/apply` |
| `getCharge(transactionId)` — after a lost answer | `POST /merchant/pay/get` |
| `reverse(successTransId, reason)` — the whole transaction | `POST /merchant/pay/reverse` |
| `hold(splits, cardToken, account, durationMinutes)` → holdId | `POST /hold/multiple/create` |
| `confirmHold(holdId, otp)` | `PUT /hold/multiple/apply/{id}` |
| `captureHold(holdId)` — always the full amount | `POST /hold/{id}` |
| `releaseHold(holdId)` | `DELETE /hold/{id}` (`send_cancel_sms=true`) |
| `getHold(holdId)` | `GET /hold/{id}` |

- A split names the stadium owner's ATMOS recipient (`owner_legal_details.atmos_recipient_id`,
  the only place it is stored — see "Owner legal details") or the platform, whose
  recipient the provider takes from `ATMOS_PLATFORM_RECIPIENT_ID`.
- `account` is our payment row id: the row is written first (`processing`), so a retry
  never charges twice.
- A declined card is a `FAILED` outcome. `STPIMS-ERR-092` (transaction closed) and `-100`
  (already reversed), `-145` (hold cancelled) and `-144` (hold expired) are
  `TransactionClosedException`, `HoldCancelledException`, `HoldExpiredException`; any
  other code is a `PaymentProviderException` carrying it — logged, never shown raw to a
  player. The codes we act on live in `provider/AtmosCodes` (with the ones ATMOS does not
  document marked as ours). No answer is a `ProviderTimeoutException`: the payment stays
  `processing`, never assumed failed, never sent again.
- **A refusal is never a 500.** A charge or hold refused while being created: an
  unreachable provider (`AtmosCodes.isUnavailable`: HTTP 5xx, `STPIMS-ERR-024/-026/-041/
  -094/-102`) is **502** "To'lov tizimi vaqtincha ishlamayapti. Birozdan so'ng qayta urinib
  ko'ring" and nothing is kept; any other refusal (removed card, under the minimum) a
  `failed` row with `failure_reason`. Refused after creation: left `processing` for the
  reconcile job. Card binding refusals are **400 with a `code`** (`CardBindingException`):
  `CARD_NOT_SUPPORTED` "Faqat Uzcard va Humo kartalari qabul qilinadi", `CARD_EXPIRY_INVALID`,
  `CARD_EXPIRED`, `SMS_CODE_WRONG`, `SMS_CODE_EXPIRED`, `SMS_RESEND_TOO_EARLY`,
  `SMS_RESEND_UNAVAILABLE`, `BINDING_NOT_FOUND`, `CARD_BINDING_FAILED`; unreachable → 502.
- **`AtmosPaymentProvider`** is active only under the `atmos` profile and refuses to
  start unless `ATMOS_BASE_URL`, `ATMOS_CONSUMER_KEY`, `ATMOS_CONSUMER_SECRET`,
  `ATMOS_STORE_ID` and `ATMOS_PLATFORM_RECIPIENT_ID` are set. Token from `POST /token`
  (Basic auth, `client_credentials`), cached until `expires_in` − 5 min; a 401 refreshes
  it once. `ofd_items` are sent only when `ATMOS_OFD_CODE_RENTAL` and
  `ATMOS_OFD_CODE_SERVICE` are both set; `ofd_url` is saved on the payment. It never
  logs a request or response body — only operation names, ids and result codes.
- **`FakePaymentProvider`** is the default in dev and tests (`app.payments.provider=fake`).
  It is never created under `atmos` and the application refuses to start with it under
  `prod` (`FakeProviderGuardTest`). It answers like ATMOS, with ATMOS's codes, behind the
  same interface — switching to `AtmosPaymentProvider` changes nothing above it. Its state
  (bindings, cards with balances, charges and holds with their splits, reverses) is in the
  `fake_payment_*` tables (changeset 044, `JdbcFakeProviderStore`, plain JDBC — no
  entities), so it **survives a restart**; each call commits on its own (`REQUIRES_NEW`),
  so our rollback never undoes what "the provider" did. Tokens are kept only as SHA-256,
  card numbers and SMS codes not at all. ATMOS keeps 1.5% of each split (rounded so the
  recipient gets `netAfterFee`); every split must be ≥ 500 so'm; a reverse is whole, once,
  within 3 months; a hold lapses after its duration; release is idempotent; the same
  account never charges or holds twice. Settings `app.payments.fake.*`: `otp-length` (6),
  `otp-ttl-seconds` (180), `resend-cooldown-seconds` (60), `starting-balance` (10 000 000
  so'm), `min-amount` (500 so'm), `refund-window-months` (3), `delay-min-ms` /
  `delay-max-ms` (300 / 800; `@DatabaseTest` sets 0).
- `GET /api/dev/fake-payments?limit=` (dev profile + `X-Dev-Login-Key`, no JWT; 404
  otherwise): the latest fake cards (masked, balance, scenario), charges and holds (status,
  splits, commission, what each recipient receives) and reverses. No token anywhere.

##### To simulate X in dev, do Y (fake provider)

| To simulate | Do |
|---|---|
| A card that works | any 16 digits starting `8600` (Uzcard) or `9860` (Humo), expiry `MM/YY` not passed, e.g. `8600 1234 1234 1234`, `12/29` — no Luhn check |
| A card we don't take | any other prefix, e.g. `4111…` → 400 `CARD_NOT_SUPPORTED` |
| A bad / past expiry | `13/29` → `CARD_EXPIRY_INVALID`; last month → `CARD_EXPIRED` |
| The SMS code | any 6 digits (`app.payments.fake.otp-length`) |
| A wrong code | `000000` → `SMS_CODE_WRONG` (the binding stays open) |
| An expired code | `999999`, or wait 180 s → `SMS_CODE_EXPIRED` |
| Resending the code | `POST /api/cards/resend {bindingId}`; within 60 s → `SMS_RESEND_TOO_EARLY` |
| A declined card | card ending `0002` (`STPIMS-ERR-058`): booking stays `pending`, row `failed` |
| Insufficient funds | ending `0051` (`-057`), or spend the 10 000 000 so'm balance (`-112`) |
| An expired card at payment | ending `0054` (`-067`) |
| A lost answer that did charge | ending `0091`: the payment stays `processing`; the reconcile job (or `getCharge`) finds it paid. On a hold it is the **capture** that times out (and went through): a join answers 202 and the job confirms the game |
| A lost answer that did not | ending `0092`: `processing`, then `failed` on reconciliation. On a hold the capture times out and the hold is gone: the job undoes the join |
| The provider down | ending `0099`: every charge / hold → 502 |
| A refund the provider refuses | ending `0098`: payments work, every reverse is refused (`-15`) → `refund_failed`, then admin retry / mark-refunded |
| A team game whose capture fails | creator's card ending `0097`: the hold works, the capture is refused with an ordinary code (`STPIMS-ERR-058`) and the hold stays → the joiner is reversed, the hold released, the game ends |
| A hold that lapses | create a team game, then let `hold_expires_at` pass (or move `games.starts_at` inside 3 h and let `GameTimeoutJob` release it) |
| An amount ATMOS refuses | any split under 500 so'm → `STPIMS-ERR-039`, a `failed` row |
| What happened | `GET /api/dev/fake-payments` with `X-Dev-Login-Key` |
- `LegacyCheckoutProvider` (Payme/Click hosted checkout) is **legacy**, kept only as the
  no-`cardId` branch of the friends-game pay endpoint. Never use it in a new flow.

#### Payment rows

- `amount` is the gross charged (or the cash taken); `owner_amount + platform_amount =
  amount`; `card_fee_expected` is what ATMOS is expected to take, its rate on each split
  (0 for cash; on rows from before changeset 040, what the card fee and rounding added);
  `provider_commission` is what ATMOS reports. **Amounts never change after insert**;
  only statuses move forward.
- **Never reversed twice.** Every give-back, refund, capture, return and reconciliation
  first locks the payment row and re-reads its *committed* status
  (`PaymentService.stillAsSeen` → `PaymentRepository.lockStatus`, a native
  `SELECT status … FOR UPDATE`, because a JPA `findForUpdate` of an entity already loaded
  in the transaction answers with the stale copy). If another transaction has moved it
  meanwhile — refunded it, started giving it back — nothing is sent to the provider. Two
  cancellations at once (player and owner) reverse once (`NoDoubleReverseTest`).
- Statuses: `processing` (provider called, answer not in) → `paid` / `held` / `failed`;
  `held` → `captured` or `released`; a give-back goes through `return_pending` →
  `refunded` / `released`; a capture through `capture_pending` → `captured`. A give-back
  ATMOS declines leaves the original `refund_failed` and its refund row `failed` (with
  `failure_reason`) until an admin retries it or marks it refunded by hand
  (`refunded_manually`, `manual_refund_reason`, `manual_refund_by`); both rows then end
  `refunded`. A **cash** row voided by undoing the owner's "cash received" mark becomes
  `cancelled` (changeset 050) — not a refund, no money moved through the platform.
- `provider_txn_id`: the charge's transaction ids (comma-separated, one per split) or the
  hold id. `provider_success_txn_ids`: what a reverse needs. `hold_expires_at`: when an
  unsettled hold lapses.
- A refund is a NEW row with the negative gross (fee included) and `refund_reason`.
- Payment types: `booking`, `remainder`, `team_game`, `game_share`, `service_fee`,
  `refund`; `game_team_id` ties a team game's payments to its (game, team).

#### Team game money → ATMOS operations

| Rule | The creating team (hold) | The other team (charged) |
|---|---|---|
| Creating a game | **hold** for (minutes to the 3-hour point) + 120, capped at `app.payments.hold-max-minutes` (43 200); longer → the game cannot be created | — |
| Waiting, slot taken by a booking | **release** | — |
| Waiting, 3-hour point (`GameTimeoutJob`) | **release** (already released counts as done) | — |
| Waiting, leader cancels before the deadline | **release** | — |
| Waiting, leader cancels after the deadline | **capture** (money kept) | — |
| Waiting, leader cancels past the 3-hour point | **release** | — |
| Opponent joins | **capture**, last | **charge** |
| Confirmed, a leader cancels before the deadline | the leaver's money: **reverse** | the team that stays: nothing |
| Confirmed, a leader cancels after the deadline | the leaver's money: kept | the team that stays: nothing |
| Confirmed, a leader cancels past the 3-hour point | the leaver's money: kept | the team that stays: **reverse** |
| Owner cancels the booking / admin blocks the stadium | **reverse** | **reverse** |
| A team's remainder in the app | **charge**; reversed with the rest of that team's money when it is given back | |

Each row is tested in `TeamGameOperationMappingTest`. Joining (see "Joining" under
team_vs_team, tested end to end in `TeamGameJoinPaymentTest`): (1) book the slot
(EXCLUDE constraint), (2) charge the joiner, (3) capture the waiting team's hold, each
step committed. If (2) is declined or unanswered the join is dropped and the game keeps
waiting (an unanswered charge is given back by reconciliation). If (3) is refused with
any code the joiner's charge is reversed, the hold released, the booking deleted, the
game cancelled and both teams notified; if (3) has no clear answer it is left
`capture_pending` (202 `PAYMENT_PENDING`) and the reconcile job confirms or undoes the
game. A join waits while the creating team's hold is still `processing`.

Ordinary bookings and friends games use **charge / reverse**: a refund (player before
the deadline, owner at any time, admin block) reverses every split's transaction in
full. Friends games never held money — each player pays as they go and the slot is
booked once everyone has — so they pay by card (`POST /api/games/{id}/pay?cardId=`)
and are reversed if the game is called off. Cash is never touched.

#### Reconciliation

`PaymentReconcileJob` runs every five minutes and settles every payment stuck for over
five minutes in `processing`, `return_pending` or `capture_pending`, each in its own
transaction:

- `processing` charge → `getCharge`: paid → the booking is confirmed (through
  `applySuccessfulBookingPayment`), the friends game told, the team's remainder settled
  — or the money given straight back if what it paid for has gone (an abandoned join);
  unconfirmed after 30 minutes → failed, no money moved.
- `processing` hold → `getHold`: held, or failed (the waiting game it was for ends).
- `return_pending` → reversed if the money was taken, released if it is held. A reverse
  ATMOS declines becomes a failed refund (`refund_failed` + a `failed` refund row), so
  the job stops trying; a release that fails stays `return_pending` for the next run.
- `capture_pending` → captured if still held; released with an error if it lapsed.
- Then the **team joins** still `joining` after five minutes are resumed
  (`GameService.resumeStuckJoins` → `TeamGameService.resumeJoin`): confirmed or undone
  from the rows just settled. A joining team's own charge is never given back by the
  payment pass — the join decides. `return_pending` is finished by
  `PaymentService.completeReturn`, the same code the join's undo uses.

Idempotent: settled rows are not picked up, and a reverse or release of something
already gone counts as done. The job never touches `failed` or `refund_failed` rows —
from then on only an admin retries them, so the two never race.

#### Saved cards

`saved_cards` keeps the provider token **encrypted at rest** (AES-256-GCM,
`CARD_TOKEN_KEY`; required under the `prod` and `atmos` profiles), the provider's card
id, a masked number (`8600 •••• •••• 1234`, `provider/CardMask` for both providers; rows
saved earlier keep `8600 •••• 1234`), the expiry ATMOS reports and an active flag. The
card number and the expiry the player typed go straight to ATMOS and are **never stored
or logged**; the token never appears in any API response. The player sees only the
masked number and expiry; deleting a card removes it at ATMOS (`removeCard`) and sets
`is_active = false`. Endpoints: `POST /api/cards` (→ `bindingId`, `maskedPhone` like
`+998 ** *** ** 58`), `POST /api/cards/resend {bindingId}` (a new code after 60 s),
`POST /api/cards/confirm`, `GET /api/cards`, `DELETE /api/cards/{id}`. Refusals: see "The
provider" above.

**Never logged or returned**: card numbers, expiry dates, SMS codes, card tokens, ATMOS
credentials or access tokens. The request DTOs and provider records override
`toString` to mask them, and a test checks it.

#### Open ATMOS questions

- [ ] `store_id` vs `terminal_id`: which identifies a split's recipient (the bulk and
      hold requests use `store_id`, the responses `terminal_id`).
- [ ] Bulk `pay/create`: `store_id` per `params` item (the table) or one at the top (the
      example)? We send both. Are `ofd_items` accepted per item?
- [ ] `amount`: a number (bulk example) or a string (hold examples)? We follow each example.
- [ ] The OTP to send when applying a token payment or hold — the docs show `111111`.
- [ ] Do hold capture/cancel/get take the multi-hold `parent_id` or each item's `hold_id`?
      We use `parent_id`.
- [ ] How is a captured hold reversed — which transaction id? We send the hold id.
- [ ] `pay/get` and `hold/{id}` status values are not listed; `confirmed: true` is read as
      success / held.
- [ ] `hold_till`'s format; and whether `DELETE /hold/{id}` answering `STPIMS-ERR-145`
      means "released" (its example) or an error.
- [ ] The token lifetime: the text says 3600 s, the example 2525.
- [ ] `/token`: `grant_type` in the form body (the docs) or the query string?
- [ ] Card binding: no way to resend the SMS code is documented (only `pay/retry-otp`, for
      payments). `AtmosPaymentProvider.resendBindingCode` answers `BIND_RESEND_UNSUPPORTED`
      (→ `SMS_RESEND_UNAVAILABLE`: enter the card again). Nor are codes for an expired OTP or
      a resend too soon (we use `OTP_EXPIRED`, `OTP_RESEND_TOO_EARLY`).
- [ ] The code for a reverse past the refund window (the fake uses `STPIMS-ERR-113`), and the
      window itself (3 months assumed).
- [ ] Is the 500 so'm minimum per split transaction or per payment? The fake checks each split.

### Availability Calculation

Every opening hour of the date is listed, each with a `status`:
1. Get the stadium's `open_time` and `close_time`
2. Cut into 1-hour slots
3. Today, an hour that has already started is `past`
4. A slot that overlaps any `pending` or `confirmed` booking is `booked`
5. A slot within a `stadium_closure` is `closed`; every other one is `free`
   (`available` is true exactly when `status = free`; precedence past → booked → closed)
6. For each free slot, calculate price from `pricing_rules` (match day_of_week + time range)
7. Mark slots that have a WAITING **team_vs_team** game (show "a team is looking for an opponent" in UI). Casual games are private to whoever was invited and are never marked
8. Apply any active promotion discount
9. When the deposit of the stadium's effective minimum is below the slot's player price,
   add `depositAmount` / `remainderAmount` (E3, T3; see "Payments → API fields")

**Free-slot count.** `GET /api/stadiums?date=YYYY-MM-DD` (Tashkent, default today; malformed = 400)
answers `freeSlotsCount` on each stadium: the hours of that date whose status above would be
`free`, from the one definition in `SlotRules`, which the availability endpoint uses too, so the
two cannot disagree. Two queries for the whole page (`AvailabilityService.freeSlotCounts`;
`StadiumFreeSlotsTest` counts them with 22 stadiums). A date outside the booking window (past,
or beyond `max-weeks-ahead`) answers 0, not an error, so a list left open past midnight still loads.

## Audit log

The admin panel shows **who did what and exactly what changed**. Every action that
changes data writes one entry, from admins, owners, players and the system alike.
The code lives in `audit/` (`AuditEntry`, `AuditAction`, `AuditChange`,
`AuditLabels`, `AuditEntityType`) and `service/AuditService.java`.

### What an entry records

| Field | Column | Notes |
|---|---|---|
| actor | `user_id`, `actor_name` | the user, and their name **as it was then**; none for SYSTEM |
| actor type | `actor_type` | `ADMIN`, `OWNER`, `PLAYER` or `SYSTEM` — the role the action was done in, not the user's flags (an owner booking someone else's pitch is a PLAYER there) |
| action | `action` | a stable code from `AuditAction` |
| entity | `entity_type`, `entity_id`, `entity_label` | the label is saved at the time (stadium name, user's name, "Arena, 2026-11-02 20:00 (1h)"), so the entry stays readable after a rename or delete |
| changes | `changes` | JSON array of `{ field, oldValue, newValue }` |
| reason | `reason` | the free text an action carries: reject, block, cancel, review note |
| ip | `ip_address` | ADMIN entries (taken from the request automatically) and admin logins |
| time | `created_at` | UTC |

Why columns plus one JSON column: everything the admin panel filters on is a real,
indexed column; `changes` is a JSON array in a TEXT column because nothing filters
inside it, and TEXT avoids Hibernate's JSON type mapping (which would need Jackson 2
next to Boot 4's Jackson 3). Values are normalised when written (`AuditChange`):
numbers stay numbers — **money is always tiyin** — booleans and strings stay as they
are, times/dates/UUIDs/enums become strings (instants in UTC ISO-8601, enums by their
lowercase `getValue()`), lists stay lists.

Rows written before changeset 029 keep their free-form `details` and have no actor
type, label, changes or reason; the API shows them with whatever they have.

### Writing an entry

```java
auditService.record(AuditEntry.admin(adminId, AuditAction.STADIUM_BLOCKED)
        .entity(AuditEntityType.STADIUM, stadium.getId(), AuditLabels.stadium(stadium))
        .change("status", oldStatus, stadium.getStatus())
        .reason(reason));
```

- Pick the factory by role: `admin(...)`, `owner(...)`, `player(...)`, `system(...)`.
  **Automatic consequences are SYSTEM, with no user**: scheduled jobs, a game
  cancelled because someone else took the slot, a game confirmed because the last
  payment landed, every refund and released hold. The player's or owner's own act
  is its own entry next to them.
- `change(field, old, new)` keeps only a real change, so pass every field you might
  have touched. A creation uses `created(field, value)` (old is null); a deletion
  records each removed value as `old → null`. For an edit, snapshot before and after
  and use `diff(before, after)` — `StadiumService.auditSnapshot` is the example — so
  **only the fields that changed** are recorded, and an edit that changes nothing
  writes no entry.
- Capture the old values **before** you mutate the entity.
- Labels come from `AuditLabels`, so the same kind of entity reads the same everywhere.
- One entry per action. Don't add a second, unstructured one for the same event.
- `record` never throws, so auditing can never break the flow it records.
- Tests: `audit/Audited` (test sources) reads back what a mocked `AuditService`
  recorded — `Audited.one(auditService, AuditAction.X)`.

### What is never logged

**Passwords, password hashes, card numbers, card tokens (`providerToken`), JWTs, the
bot token or any other secret** — not as a field, not in a label, not in a reason.
`AuditEntry.change` drops any field whose name looks like one (password, hash, token,
card number, cvv, secret, jwt) and logs an error, but that is a safety net, not
permission: never pass such a value in the first place. A walk-in client's phone
number is not logged either; their name is part of the booking label.

### Logged actions

| Area | Actions |
|---|---|
| Admin | `ADMIN_LOGIN_SUCCEEDED`, `ADMIN_LOGIN_FAILED`, `APPLICATION_APPROVED`, `APPLICATION_REJECTED`, `STADIUM_APPROVED`, `STADIUM_REJECTED` (pending → rejected; before changeset 038, pending → blocked), `STADIUM_BLOCKED`, `STADIUM_UNBLOCKED` (blocked → approved), `STADIUM_STATUS_CHANGED` (back to pending) — each with `status` and `statusReason` changes, `STADIUM_NOTE_ADDED` (the note's id, never its text), `STADIUM_BOOKING_TERMS_CHANGED` (`minBookingAmount`, `cancellationHours`), `USER_BLOCKED`, `USER_UNBLOCKED`, `USER_ROLE_CHANGED`, `OWNER_LEGAL_DETAILS_CHANGED`, `REFUND_RETRIED`, `REFUND_MARKED_MANUAL`, `SETTINGS_CHANGED` |
| Owner | `STADIUM_CREATED`, `STADIUM_UPDATED` (incl. `pricePerHour`, `landmark`, photos, amenities — the four of changeset 048 too; rows from before the rework may carry `minBookingAmount` / `cancellationHours`), `PRICING_RULE_CREATED` / `_UPDATED` / `_DELETED`, `CLOSURE_CREATED` / `_DELETED`, `PROMOTION_CREATED` / `_DEACTIVATED`, `MANUAL_BOOKING_CREATED`, `CASH_RECEIVED` (a booking — with `remainderMark` — or one team's game remainder, with `teamX.remainderMark`), `REMAINDER_NOT_PAID` (a booking or a team), `REMAINDER_MARK_UNDONE` (also ADMIN, for the admin's undo), `BOOKING_CANCELLED_BY_OWNER` |
| Player | `BOOKING_CREATED`, `BOOKING_CANCELLED_BY_USER`, `BOOKING_REMAINDER_PAID`, `BOOKING_PAYMENT_RETRIED`, `BOOKING_SERIES_CREATED` / `_CANCELLED`, `GAME_CREATED`, `GAME_JOINED`, `GAME_LEFT`, `GAME_CANCELLED`, `GAME_OPPONENT_LEFT`, `GAME_REMAINDER_PAID`, `PROFILE_UPDATED` (also a language picked in the bot), `PHONE_SHARED_VIA_BOT` (`phoneChanged`, never the number) |
| System | `BOOKING_CONFIRMED` (a payment confirmed it), `BOOKING_EXPIRED`, `BOOKING_COMPLETED`, `BOOKING_CANCELLED_BY_OWNER` (by a stadium block), `GAME_CONFIRMED`, `GAME_AUTO_CANCELLED`, `PAYMENT_COMPLETED`, `PAYMENT_RELEASED`, `REFUND_CREATED`, `REFUND_FAILED` (ATMOS declined; the reason is its error), `PROMOTION_EXPIRED` |

Codes are **stable**: they are stored and filtered on, so never rename one — add a
new constant. Legacy rows may carry older codes (e.g. `PLATFORM_SETTINGS_UPDATED`,
`GAME_CANCELLED` written by the system); the action filter still accepts them.

### Reading it: admin endpoints

- `GET /api/admin/audit-log` — newest first, paged (`page`, `size`). Filters, all
  optional and combined with AND:
  - `actor` — matches the actor's current name, username or phone, or the name
    saved on the entry (case-insensitive, literal — `%` and `_` are not wildcards)
  - `actorType` — `ADMIN` / `OWNER` / `PLAYER` / `SYSTEM` (anything else → 400)
  - `action` — one or several: `?action=A&action=B` or `?action=A,B`
  - `entityType`, `entityId`
  - `from`, `to` — **Tashkent** calendar days, both inclusive (`to` before `from` → 400)

  Each item:

  ```json
  {
    "id": "…", "time": "2026-10-28T11:52:42.439Z",
    "actor": { "id": "…", "name": "Dilnur Aliqulov", "type": "ADMIN" },
    "action": "SETTINGS_CHANGED",
    "entity": { "type": "platform_settings", "id": "…", "label": "Platform settings" },
    "changes": [ { "field": "teamGamePlatformFee", "oldValue": 2500000, "newValue": 3000000 } ],
    "reason": null, "ip": "203.0.113.7"
  }
  ```

  `changes` is always an array; `actor` has only `type` for SYSTEM; absent fields are
  omitted. Old rows add `details` and may have no `actor` at all.
- `GET /api/admin/audit-log/actions` — every code in `AuditAction`, for a dropdown.
- **Entries can never be edited or deleted.** There is no such endpoint (PUT/DELETE
  answer 405/404), `AuditLog` is Hibernate `@Immutable`, and `AuditLogRepository`
  extends the bare Spring Data `Repository` with only `save` and reads — no update
  or delete method exists for any caller. Don't add one.

## Admin panel API

Everything under `/api/admin/**` needs `isAdmin` (no token → 401, anyone else → 403).
Conventions every list follows:

- **Filters are JPA specifications** (`repository/*Specifications`), combined with AND;
  every filter is optional. Don't write a JPQL query with `:x IS NULL OR …` for a list.
- **`from` / `to` are Tashkent calendar days, both inclusive**: `[from 00:00, (to + 1 day)
  00:00)` in `Asia/Tashkent`, as UTC instants — always through `util/TashkentDays`
  (`to` before `from` → 400 "to must be on or after from").
- **Search**: names case-insensitively, phones ignoring spaces and `+` on both sides
  (`repository/SearchPredicates`); `%` and `_` are literal.
- **No N+1 on a list**: a page fetches its to-one associations in the row query
  (`FetchJoins`; a stadium's `amenities` is an inverse one-to-one Hibernate always loads,
  so fetch it too) and loads everything else for the whole page in one grouped query
  each. Read `platform_settings` once per list (`CardPricing.snapshot()`), not per row.
  Each list has a test asserting its query count with 20+ rows.
- Every admin write is audited with old → new values; unknown filter values are 400.

### Endpoints

| Endpoint | What |
|---|---|
| `GET /api/admin/bookings` · `/{id}` | every booking, newest `startsAt` first. Filters `search` (booking user or walk-in client), `stadiumId`, `status`, `from`/`to` (on `startsAt`), `userId`. `AdminBookingResponse` = the admin `BookingResponse` (`price` the player pays, `ownerPrice`, `paidInApp` (card money kept), `ownerReceives`, `atmosFee`, `paidInCash`, `remainderMark`, `remainderMarkedAt`, …) + `userName`, `userPhone`, `cashReceived`, `cashReceivedAt` |
| `DELETE /api/admin/bookings/{id}/remainder-mark` | undo the owner's remainder mark at any time (E6; "cash received" voids that cash row); the `AdminBookingResponse`, "Mark undone"; no mark 409 "This booking has no remainder mark" — see "Paying for a booking → Owner marks" |
| `GET /api/admin/games` · `/{id}` | every game, newest `startsAt` first. Filters `search` (creator or any team's name), `stadiumId`, `status`, `from`/`to`, `userId` (created, played in, or led a team in). Detail: team games get `teams` (the `GameTeamResponse` money + `players`), casual games `participants` and `serviceFeePerPerson`. `cancelledAt` / `cancellationReason` come from the audit entry that cancelled the game |
| `DELETE /api/admin/games/{id}/remainder-mark?side=a\|b` | undo the owner's mark on one team's remainder at any time (T10; "cash received" voids that cash row); the admin game detail, "Mark undone"; no mark 409 "This team has no remainder mark" — see "team_vs_team → Owner marks on a team's remainder" |
| `GET /api/admin/payments` · `/{id}` | every payment and refund, newest first (`type=refund`: failed refunds first). Filters `search` (payer name/phone, `providerTxnId`), `type`, `status`, `method`, `stadiumId` (booking's or game's), `from`/`to` (on `createdAt`). Each has its split, `atmosFee` (`card_fee_expected`), `providerCommission`, `failureReason`, `refundedManually`, `manualRefundReason`; the detail adds `linkedPayments` (a refund's original, or an original's refunds) |
| `GET /api/admin/payments/summary?from=&to=` | both required. `appRevenue`, `cardFees`, `ownerPayouts`, `platformRevenue`, `refunded`, `held` — see "Revenue" |
| `POST /api/admin/payments/{id}/retry-refund` | a `failed` refund, tried again (see "Failed refunds") |
| `POST /api/admin/payments/{id}/mark-refunded` | `{ reason }` 10–500 chars: a `failed` refund returned outside the platform |
| `GET /api/admin/stadiums` | every status. Filters `status` (incl. `rejected`), `search`, `district`, `format`, `ownerId`. Rows add `photoUrl`, `district`, `pricePerHour` (the owner's), `playerPrice`, `ownerReceives`, `atmosFee`, `ownerContractStatus`, `statusReason` |
| `GET /api/admin/stadiums/{id}` | the public detail mapping for **any** status, in the owner's prices (`pricePerHour`, `minBookingAmount`) with `playerPrice`, `playerMinBookingAmount`, `ownerReceives`, `atmosFee` + `pricingRules` (the same) + the list's figures |
| `PUT /api/admin/stadiums/{id}/status` | `approved` (needs the owner's signed ATMOS contract, then ≥ 3 photos), `rejected` (only from pending, reason required), `blocked` (reason required), `pending` |
| `PUT /api/admin/stadiums/{id}/booking-terms` | `{ minBookingAmount: Long\|null, cancellationHours: Integer }` — see "Cancellation Policy"; any status; answers the same detail as `GET /api/admin/stadiums/{id}` (which carries `landmark`, `amenities`, `photos`, `latitude` / `longitude`, `cancellationPolicy`, `cancellationHours`, `minBookingAmount`) |
| `GET` / `POST /api/admin/stadiums/{id}/notes` | admins' internal notes, newest first; `{ text }` 1–1000 chars. Never shown to owners or players |
| `GET /api/admin/users` | filters `search`, `role` (`player` = neither owner nor admin, `owner`, `admin`), `status` (`active`, `blocked`) |
| `GET` / `PUT /api/admin/users/{id}/legal-details` | see "Owner legal details"; GET answers `data: null` before anything is entered |
| `GET /api/admin/users/{id}/teams` | the teams a user leads or belongs to: `id`, `name`, `leaderName`, `memberCount`, `role` |
| `GET /api/admin/stats` | overview figures (see "Revenue"), plus `failedRefunds` / `oldestFailedRefundAt`, `newUsersLast7Days`, `rejectedStadiums`, and the previous-period values `todayBookingsLastWeek`, `previousMonthRevenueToDate`, `newUsersPrevious7Days`, `approvedStadiumsWeekAgo` (the panel works out the %) |
| `GET /api/admin/stats/revenue` | `appRevenue` per Tashkent day, last 30 days, every day present; `bookingCount` = the booking series below |
| `GET /api/admin/stats/bookings-daily` | bookings starting each of the last 30 Tashkent days (confirmed or completed, like `todayBookings`), every day present |

### Revenue

`RevenueService` is the **only** revenue calculation: the payments summary, the overview's
`totalRevenue` / `todayRevenue` / `weekRevenue` / `monthRevenue` /
`previousMonthRevenueToDate`, the 30-day chart and a stadium's `totalRevenue` all read
it, so they always agree. Don't add another sum over payments for these figures. Cash
is excluded from all of them ("ilova orqali").

- **appRevenue** — card money kept: the gross of every card payment whose money arrived
  (`paid`, `captured`, `completed`, or since `refunded` / `refund_failed`), counted when
  it was taken, less every card refund that completed in the period (counted when its
  row became `refunded`).
- **cardFees** — what ATMOS took of that money: its reported `provider_commission`;
  estimated at the current `card_fee_bps` when none was reported; 0 on legacy
  Payme/Click rows. A reversal is taken to give the fee back too; a refund made by hand
  did not touch the card transaction, so ATMOS keeps its fee.
- **ownerPayouts** / **platformRevenue** — what each actually receives: its split less
  its part of ATMOS's fee (ATMOS charges the same rate on every split, so the owner bears
  `fee × ownerAmount / amount`, the platform the rest).
- `ownerPayouts + platformRevenue + cardFees = appRevenue` holds for every payment, so
  for any sum of them (`RevenueTest`).
- `refunded` — card refunds completed in the period, positive; `held` — everything on
  hold on players' cards right now, whatever the period.

The owner dashboard's revenue is still its own definition (see "Deferred until the
payment model is final").

### Failed refunds

A refund ATMOS declines is saved `failed` (its original `refund_failed`) instead of
rolling back the cancellation — see "Cancellation Policy". Two ways out, both on the
`failed` refund row, which they lock:

- **retry-refund** reverses the same transactions again on the same row; one already
  reversed answers "closed" and counts as done, so however often it is retried the money
  goes back once. A decline is a normal 200 (still `failed`, new `failureReason`);
  ATMOS unreachable is 502 "Payment provider unavailable" and nothing changes. Success:
  both rows `refunded`, audit `REFUND_RETRIED`.
- **mark-refunded** records money returned outside the platform: both rows `refunded`,
  `refundedManually`, `manualRefundReason`, `manual_refund_by`; audit
  `REFUND_MARKED_MANUAL`.

Only a `failed` refund qualifies (400 "Only a failed refund can be retried" / "… marked
as refunded"). `GET /api/admin/stats` counts them for the sidebar badge.

### Owner legal details

`owner_legal_details` (1:1 with users, `OwnerLegalDetailsService`): `legalType`
(`yatt`, `mchj`, `self_employed`), `tin` (9 digits; the 14-digit PINFL for
`self_employed` — "tin must be 9 digits (or 14 for self-employed)"),
`atmosContractStatus` (`none`, `pending`, `signed`) and `atmosRecipientId` (1–50 chars,
required when signed, ignored otherwise). **This is the only place an owner's ATMOS
recipient id is stored**; the payment split reads it from here. A stadium cannot be
approved until its owner's contract is signed. Changes are audited
(`OWNER_LEGAL_DETAILS_CHANGED`) with who made them (`updatedByName`). Rows moved from the
old `users.atmos_recipient_id` by changeset 037 are `signed` with no legal type or tin
until an admin fills them in.

**The owner's own view** (R39): `GET /api/owner/legal-details` (owner role) answers
`{ legalType?, atmosContractStatus, ownerContractStatus }` — `ownerContractStatus` is the
same value as `atmosContractStatus`, under the admin stadium list's name. Never `tin`,
`atmosRecipientId` or who changed it. With no row it answers what the admin side shows for
such an owner: both statuses `none`, no `legalType` (the admin's own
`GET /api/admin/users/{id}/legal-details` still answers `data: null` then).

## Owner API

Owner endpoints live under `/api/owner/**` (owner role; see "Endpoint Security" for the
exceptions: adding and listing stadiums, and their photos). Every per-stadium endpoint checks ownership the same way, in any stadium status:
**404 "Stadium not found"**, then **403 "You do not own this stadium"**
(`StadiumService.requireOwnedStadium`). Prices are the owner's own, with the player price
alongside (`playerPrice`, `playerMinBookingAmount`).

| Endpoint | What |
|---|---|
| `POST /api/owner/stadiums` · `GET /api/owner/stadiums` | add a stadium (starts `pending`) · the caller's stadiums with `status` / `statusReason` — any signed-in user (R42) |
| `GET /api/owner/stadiums/{id}` | one of the caller's stadiums, any status: the `StadiumDetailResponse` that `PUT` returns, built by the same code (R35) |
| `PUT /api/owner/stadiums/{id}` | partial update (only non-null fields, each checked as on create — see "Stadiums"); audited `STADIUM_UPDATED`. No booking terms or photo URLs: `minBookingAmount`, `cancellationHours`, `clearMinBookingAmount`, `photos` are ignored if sent (the admin's booking-terms endpoint; the photo upload) |
| `POST /api/owner/stadiums/{id}/photos` | multipart field `file` → `PhotoResponse {id,url,position}`, "Photo uploaded" (R36, see "Stadium photos") — any signed-in user (D6) |
| `DELETE /api/owner/stadiums/{id}/photos/{photoId}` | `data: null`, "Photo deleted"; the rest renumbered 0..n-1; 404 "Photo not found" if not this stadium's — any signed-in user (D6) |
| `GET /api/owner/legal-details` | the caller's own `{ legalType?, atmosContractStatus, ownerContractStatus }` (R39), read-only; see "Owner legal details" |
| `GET /api/owner/dashboard/stadiums/{id}/revenue?days=` | `{ stadiumId, dailyRevenue: [{ date, revenue, bookingCount }] }`: `days` 7 or 30 (default 30, anything else 400 "days must be 7 or 30"); **every** Tashkent day of the window, oldest first, ending today, `0` / `0` on a day without payments (R43). Revenue as before: completed `booking` payments, by `created_at` |
| `GET /api/owner/dashboard/stadiums/{id}/bookings?unpaid=true` | (R38) only **live** bookings (`pending`, `confirmed`, `completed` — not cancelled or expired) with `remainingAmount > 0`, soonest `startsAt` first, paged, owner-view `BookingResponse`; `from`/`to` still apply. Filtered in the query (`BookingSpecifications.ownerUnpaid`, remaining computed in SQL as `Booking.getRemainingAmount` does) |
| `GET /api/owner/dashboard/stadiums/{id}/stats` | the counts and revenue as before, plus (R38, tiyin) `todayPaidInApp`, `todayPaidInCash`, `todayOutstanding`, `weekPaidInApp`, `weekPaidInCash`, `weekOutstanding`, `monthPaidInApp`, `monthPaidInCash`, `monthOutstanding`. PaidInApp / PaidInCash: payments of the stadium's bookings and of its team games' teams (`game_team_id`) whose money arrived (`paid`, `captured`, `completed`), by method `card` / `cash`, counted by the payment's `created_at` in the Tashkent period — one grouped query for all three periods. Outstanding: the sum of `remainingAmount` (`BookingResponseFactory.remainingAmount`, what the responses show) of live bookings **starting** in the period — one query. Periods: today, this Monday–Sunday week, this calendar month |
| `POST /api/owner/bookings/{id}/cash` | the rest of a booking taken in cash (R37), mark `cash_received` (E5) — see "Paying for a booking → Owner marks" |
| `POST /api/owner/bookings/{id}/not-paid` | mark the rest as not paid (E5): no charge, no penalty; "Marked as not paid" |
| `DELETE /api/owner/bookings/{id}/remainder-mark` | undo the mark until the end of its Tashkent day (E6), else 409 "Only an admin can change this mark"; "Mark undone" |
| `PUT /api/owner/stadiums/{id}/photos/order` | `{ photoIds: UUID[] }` = every photo exactly once, else 400 "photoIds must list every photo of the stadium exactly once" → `PhotoResponse[]` in order, "Photos reordered" — any signed-in user (D6) |

### Stadium photos

`StadiumPhotoService` / `StadiumPhotoStorage` (R36). Any stadium status. **Any signed-in
user** may call the three photo endpoints (D6: a player's add-stadium wizard uploads the
photos of their still-pending stadium before they have the owner role); ownership is
still checked, 404 / 403, as on every owner endpoint.

- **Upload:** JPEG, PNG or WebP, told by the **magic bytes** (`util/ImageType`), never the
  client's content type or file name → otherwise 400 "Unsupported image type". Over 5 MB
  → **413 "File too large"** (the service checks it, and Spring's multipart limit —
  `spring.servlet.multipart.max-file-size: 5MB`, `max-request-size: 6MB` — maps
  `MaxUploadSizeExceededException` to the same 413). At most 10 photos → 400 "A stadium
  can have at most 10 photos". A missing `file` part is 400 "file is required"; a
  non-multipart body 415.
- **Every photo is re-encoded** (`util/ImageReencoder`, D5): the size is read from the
  header first — over 40 megapixels is 400 **"Image is too large"** before anything is
  decoded (a decompression bomb; very large ones are read subsampled); then decoded (WebP
  through `com.twelvemonkeys.imageio:imageio-webp`) — anything that does not decode
  (truncated, garbage after the magic bytes, a CMYK JPEG) is 400 "Unsupported image type";
  transparency flattened onto white; scaled so the long side is **≤ 2000 px** (bilinear
  halving, then one bicubic step); written as **JPEG quality 0.85 with no metadata**, so
  EXIF (GPS, camera) never reaches the disk. EXIF orientation is not applied — the Mini
  App bakes it in before uploading. Tests: `ImageReencoderTest` (the WebP samples are
  checked-in 1×1 files in `src/test/resources/images`, `TestImages` makes the rest).
- Stored on the local filesystem under `app.uploads.dir` (`UPLOADS_DIR`, default
  `./uploads`, git-ignored) as `stadiums/<stadiumId>/<random uuid>.jpg` (photos stored
  before the re-encoding may still be `.png` / `.webp`, and are still served); the
  stored `url` is `/api/uploads/stadiums/<stadiumId>/<file>`. A new photo goes last
  (`max(position) + 1`); the lowest position is the cover (`coverPhotoUrl`). The stadium
  row is locked for each change, so concurrent uploads cannot pass the limit.
- Files follow the database: a written file is removed if the transaction rolls back; a
  deleted photo's file goes after the deletion commits. URLs that are not ours (photos
  sent as URLs on create/update before that was removed) are never touched on disk.
- **Serving:** `GET /api/uploads/stadiums/{stadiumId}/{file}` is public (`permitAll` on
  `GET /api/uploads/**`), with the image's Content-Type, `Cache-Control: max-age=31536000,
  public, immutable` and `nosniff`. Only a `<uuid>.<jpg|png|webp>` name under a stadium id
  resolves; anything else (directories, other names, `..`) is 404/400 — no listing, no
  traversal (`StadiumPhotoTest`).
- Audited as `STADIUM_UPDATED` with the `photos` URL list before → after.

## Auth

### Telegram Mini App (primary)
1. Client sends raw `initData` string to `POST /api/auth/telegram`
2. Server verifies HMAC-SHA256 signature using bot token:
   - Create secret: `HMAC-SHA256("WebAppData", bot_token)`
   - Compute hash: `HMAC-SHA256(secret, data_check_string)`
   - Compare with `hash` from initData
3. Extract user info from verified data
4. Find or create user by `telegram_id` (a new user: `name` from Telegram, `language = uz`)
5. Return JWT

**A blocked user** gets 403 `ACCOUNT_BLOCKED` "Account is blocked" — from this sign-in and
from `JwtAuthFilter` on every request — with `data: { reason, blockedAt }` from the latest
`USER_BLOCKED` audit entry (`AccountBlockService`): the reason as the admin typed it
(absent when none) and when (UTC).

### Dev-only sign-in (`dev` profile)

For testing the Mini App in a plain browser, without Telegram `initData`:
`GET /api/auth/dev/users` (the 50 newest users: `id`, `name`, `isOwner`, `isAdmin`,
`isBlocked`) and `POST /api/auth/dev/login` `{ "userId" }` → the same `{ token, user }`
as the Telegram sign-in (400 "Invalid id", 404 "User not found", 403 `ACCOUNT_BLOCKED`).
`DevAuthController` / `DevAuthService` exist **only under `SPRING_PROFILES_ACTIVE=dev`**;
in every other profile the paths are 404. `DevProfileGuard` makes the application
**refuse to start** when `dev` is active together with `prod` or `atmos`, or without a
`DEV_LOGIN_KEY` (`app.dev.login-key`) of at least 16 characters. Every `/api/auth/dev/*`
request must send that key in **`X-Dev-Login-Key`**: `DevLoginKeyInterceptor` compares
SHA-256 digests in constant time, and a missing or wrong key answers exactly like an
unknown path — 404 "Not found" — before the body is read. Never enable it on a server.
The same key guards `GET /api/dev/fake-payments` (see "Payments → The provider").

### Telegram Login Widget (web dashboard)
- Same flow, different payload format
- Same JWT returned, same user record

### Admin accounts

Platform admins sign in to the admin web panel with a **username and password**,
not Telegram. They are a handful of people the platform owner creates directly in
the database. Players and stadium owners keep signing in through Telegram; nothing
changes for them. All of this lives in `service/AdminAuthService.java`.

**Passwords are managed only in the database.** There is deliberately no API to
set or change one — not even an admin's own: the platform owner creates, resets
and unlocks accounts with the SQL below. Don't add such an endpoint.

- `POST /api/auth/admin/login` with `{ "username", "password" }` (public) returns
  the same `{ token, user }` as the Telegram sign-in. Only a user with
  `is_admin = true` **and** a `password_hash` can sign in this way. Usernames
  match case-insensitively (unique index `uq_users_username_lower`).
- Wrong username or wrong password → **401 `INVALID_CREDENTIALS`**
  "Login yoki parol noto'g'ri" — identical in both cases, and an unknown username
  still runs a BCrypt check against a dummy hash so the response time matches.
- **5** wrong passwords in a row lock the account for **15 minutes** →
  **423 `ACCOUNT_LOCKED`** with `data.minutesRemaining`. While locked even the
  right password is refused. When the lock lifts the counter starts again at 0.
- One IP may make **20** attempts per **15 minutes**, right or wrong →
  **429 `TOO_MANY_ATTEMPTS`**. Kept in memory (`LoginRateLimiter`), which is
  enough for one server. The IP is `request.getRemoteAddr()`: behind a reverse
  proxy set `server.forward-headers-strategy`, or every admin shares the proxy's IP.
- A blocked admin → **403 `ACCOUNT_BLOCKED`**, but only once the password is
  right, so the block tells a guesser nothing.
- Success resets `failed_login_attempts`, clears `locked_until` and stores
  `last_login_at`. Successes and failures write `ADMIN_LOGIN_SUCCEEDED` /
  `ADMIN_LOGIN_FAILED` audit entries with the username and IP.
- **A password reset in the database signs out every session.** The reset SQL
  stamps `users.password_changed_at`, and `JwtAuthFilter` (which already loads the
  user for the blocked check) refuses any token issued before that with
  **401 `TOKEN_REVOKED`**; the admin signs in again with the new password. Tokens
  carry an `iatMs` claim (issue time in milliseconds) because the standard `iat` is
  whole seconds and could not tell apart a token issued in the same second as the
  reset; a token without `iatMs` falls back to `iat`. Telegram-issued tokens of the
  same user are refused as well. Users with no `password_changed_at` are never
  checked. `now()` is the database clock, so keep the app and database hosts in
  time sync (NTP).
- Limits are `app.admin-login.*` (`max-failed-attempts`, `lock-minutes`,
  `max-attempts-per-ip`, `ip-window-minutes`), defaults 5 / 15 / 20 / 15.
- Error responses here carry an extra `code` key in the envelope. It appears only
  when set, so every other response keeps its three keys.

**Passwords are never stored or logged in plain text.** Only the BCrypt hash goes
into `users.password_hash`; audit entries and log lines carry the username and IP,
never a password; `AdminLoginRequest` overrides `toString` to mask it. The SQL
below necessarily contains the plain password in the statement itself: don't save
it in a script file or in `psql` history (`\set HISTFILE /dev/null` before running
it, or clear the history afterwards), and hand the password to the admin over a
private channel. A new password needs at least 10 characters and at most 72 bytes
(BCrypt ignores the rest).

**Create an admin** (pgcrypto is enabled by changeset 027; `id`, `language` and
the other flags take their defaults; `telegram_id` and `phone` stay null):

```sql
INSERT INTO users (username, name, password_hash, is_admin)
VALUES ('dilnur', 'Dilnur Aliqulov', crypt('a-long-unique-password', gen_salt('bf', 12)), true);
```

**Reset a password** (also clears any lockout; `password_changed_at = now()` signs out
every existing session — keep it, or a leaked token survives the reset):

```sql
UPDATE users
SET password_hash         = crypt('a-new-long-password', gen_salt('bf', 12)),
    password_changed_at   = now(),
    failed_login_attempts = 0,
    locked_until          = NULL
WHERE LOWER(username) = LOWER('dilnur');
```

**Unlock a locked account** without changing the password:

```sql
UPDATE users SET locked_until = NULL, failed_login_attempts = 0
WHERE LOWER(username) = LOWER('dilnur');
```

### JWT
- Sent as `Authorization: Bearer <token>`
- Contains: userId, phone, isOwner, isAdmin
- Expiry: 24 hours
- `JwtAuthFilter` validates on every request, sets SecurityContext

### Endpoint Security
- `/api/auth/**` → public (Telegram sign-in and `POST /api/auth/admin/login`)
- `/api/stadiums/**` GET → public (listing, detail, availability)
- `/api/bookings/**` → authenticated
- `/api/owner/**` → authenticated + isOwner check, except exactly `POST /api/owner/stadiums`
  and `GET /api/owner/stadiums` (no sub-paths): any signed-in user, so a player can add
  their first stadium and follow its review (R42); and its photos —
  `POST /api/owner/stadiums/*/photos`, `DELETE /api/owner/stadiums/*/photos/*`,
  `PUT /api/owner/stadiums/*/photos/order` — also any signed-in user (D6), the service
  checking ownership (404 / 403)
- `/api/admin/**` → authenticated + isAdmin check
- `/api/games/**` → authenticated
- `/api/teams/**` → authenticated
- `GET /api/uploads/**` → public (uploaded stadium photos, see "Stadium photos")
- `GET /api/dev/**` → no JWT; exists only under the `dev` profile, behind `X-Dev-Login-Key`
  (the fake payments view)
- `/webhooks/**` → verify provider signature, no JWT (`/webhooks/telegram`: the
  `X-Telegram-Bot-Api-Secret-Token` header, see "Telegram bot")

## Telegram bot

What a user sees in the chat before and after Start, and the buttons that open the Mini
App. Code in `telegram/`; messages go out through `service/TelegramBotService` (also used
by `NotificationService`), which calls the Bot API through `telegram/TelegramApi`.

### Receiving updates

- **webhook** (production, `TELEGRAM_BOT_MODE=webhook`, the default): Telegram calls
  `POST /webhooks/telegram` (public, no JWT). `X-Telegram-Bot-Api-Secret-Token` must equal
  `TELEGRAM_WEBHOOK_SECRET` (constant-time); otherwise **403**, and with no secret set every
  call is 403. Register it with `setWebhook … secret_token=` (see "After the first deploy").
- **polling** (`TELEGRAM_BOT_MODE=polling`, **`dev` profile only**): `TelegramPolling` calls
  `deleteWebhook`, then loops on `getUpdates`. `TelegramBotModeGuard` refuses to start with
  polling outside `dev` — so never in prod — or with any other mode value. Use a separate
  test bot: polling deletes that bot's webhook.
- Both hand each update to **`TelegramUpdateHandler`**, the one handler. It first claims the
  `update_id` in `telegram_processed_updates` (`INSERT … ON CONFLICT DO NOTHING`, its own
  transaction): a redelivered update never runs twice. A failure while handling is logged
  and not retried (the webhook still answers 200, or Telegram resends forever).
  `TelegramUpdateCleanupJob` forgets ids older than a week. Only private chats are answered.
- **Logging**: only `update_id`, the update type and the Telegram user id — never message
  text, a contact or a phone number; a phone that must be mentioned is masked
  (`PhoneMask`: `+998 90 *** ** 58`). Of an unexpected exception only the class is logged.
  The bot token is part of every API URL, so `TelegramApi` never lets a URL reach a log.
- `app.bot.enabled=false` (or no `BOT_TOKEN`) switches every Bot API call off. `@DatabaseTest`
  sets it, so tests never message real chats with the token from `.env`.

### Bot profile, set on startup

`TelegramBotSetup` runs in the background after startup (`app.bot.setup-on-startup`,
default true): `setMyDescription` (the text before Start), `setMyShortDescription` and
`setMyCommands` (`/start`, `/help`, `/language`) for **uz with no `language_code`** (the
default for everyone) and for `ru` and `en`, plus the default `setChatMenuButton`
(`web_app`, text "MiniPolya", `MINIAPP_URL`). Each value is **read first** (`getMy…`,
`getChatMenuButton`) and set only when it differs, so a restart changes nothing at
Telegram. A failure is logged and never stops the application.

### The conversation (`TelegramBotFlow`)

Every user is found or created by **`AuthService.findOrCreateFromTelegram`** — the one
place a Telegram user is created, shared with the Mini App sign-in (a new user: name from
Telegram, language uz). Every message is in the user's saved `user.language`.

- **Blocked** user: only "Hisobingiz bloklangan." plus the support contact, for any message
  or button.
- **`/start`**, no phone yet: two messages. The banner photo with the greeting and an inline
  `web_app` button "⚽ MiniPolya'ni ochish" (with the deep link, if any); then the request for
  the phone with a one-button reply keyboard, "📱 Telefon raqamni yuborish" (`request_contact`,
  resized, one-time, placeholder). With a phone: only the banner, with "Xush kelibsiz" and the
  open button. The first name is HTML-escaped.
- **Deep links**: `/start <payload>` (e.g. `game_<id>` from an invite,
  `t.me/{bot}?start=game_{id}`) — if it is a valid start parameter (`[A-Za-z0-9_-]{1,64}`),
  the open button goes to `MINIAPP_URL?startapp=<payload>`. A `web_app` button gives the Mini
  App no `start_param`, so **the Mini App reads `startapp` from its own URL**. When the phone
  is still to come, the payload is also kept in memory for an hour (`StartPayloads`) and
  carried by the button sent after the phone.
- **The contact**: accepted only if `contact.user_id == from.id` (otherwise "Iltimos,
  oʻzingizning raqamingizni yuboring…" with the keyboard again); normalised to
  `+998XXXXXXXXX`, any other country refused; a phone another account has is **never
  overwritten or merged** ("Bu raqam boshqa hisobga biriktirilgan." + support, a masked
  warning in the log). Saved by `UserService.savePhoneFromBot` (the profile's uniqueness rule,
  audit `PHONE_SHARED_VIA_BOT`), then two messages: the masked number with the keyboard
  removed, and "Endi maydonni tanlang va band qiling 👇" with the open button.
- Any other text: before the phone, the request again with the button; after it, the open button.
- **`/help`**: the three steps, the cancellation rule in one line, the support contact
  (when `SUPPORT_USERNAME` is set) and the open button.
- **`/language`**: inline buttons "🇺🇿 Oʻzbekcha", "🇷🇺 Русский", "🇬🇧 English"
  (`lang:uz|ru|en`). The choice is saved through `UserService.updateProfile` — the same
  `user.language` the Mini App uses, audited `PROFILE_UPDATED` — and the message is edited
  to confirm it.
- With `MINIAPP_URL` missing or not https (Telegram refuses such a `web_app` button) the open
  buttons are left out and the texts still go out; `TelegramBotModeGuard` logs a warning at
  startup (under `prod` it refuses to start).

### Texts and the banner

- **All bot texts** live in `src/main/resources/telegram/bot_uz.properties` (the default and
  the fallback), `bot_ru` and `bot_en`, read as UTF-8 by `BotTexts`. `{name}` placeholders
  are replaced literally (no MessageFormat, so apostrophes stay). `BotTextsTest` checks the
  three have the same keys and fit Telegram's limits. Don't put a bot text in Java.
- The banner is `src/main/resources/telegram/banner.png` (1280×640; the current one is a
  placeholder). It is uploaded on first use; Telegram's file_id is kept in
  `telegram_bot_values` (`banner_file_id`) tied to the image's hash, so replacing the PNG
  uploads the new one once. If Telegram refuses the photo, the caption goes out as text.

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
- Use constructor injection (Lombok `@RequiredArgsConstructor`), never `@Autowired` on fields
- Log important actions: booking created, payment received, game confirmed, errors
- Write unit tests for service layer, integration tests for controllers

## Environment Variables

Stored in `.env` in the project root (never committed to Git; copy `.env.example`):

```
DB_URL=jdbc:postgresql://localhost:5432/minipolya
DB_USERNAME=postgres
DB_PASSWORD=...
JWT_SECRET=...        # at least 32 bytes — HS256 refuses a shorter key at startup
BOT_TOKEN=...
TELEGRAM_BOT_MODE=webhook   # webhook (production) or polling (dev profile only, local testing)
TELEGRAM_WEBHOOK_SECRET=... # 32+ chars of A-Z a-z 0-9 _ -; required under prod (openssl rand -hex 32)
MINIAPP_URL=https://...     # the Mini App; opened by the bot's buttons and menu button (https under prod)
SUPPORT_USERNAME=           # optional, without @: the support contact the bot shows
CARD_TOKEN_KEY=...    # 32 bytes, base64; encrypts saved card tokens (required under prod/atmos)
CORS_ALLOWED_ORIGINS= # optional: browser origins allowed cross-origin, comma-separated; empty = no CORS
DEV_LOGIN_KEY=        # dev profile only: 16+ chars, sent as X-Dev-Login-Key to /api/auth/dev/*
UPLOADS_DIR=./uploads # optional: where uploaded stadium photos are stored (back it up with the DB)

# ATMOS — used only with SPRING_PROFILES_ACTIVE=atmos
ATMOS_BASE_URL=https://apigw.atmos.uz
ATMOS_CONSUMER_KEY=...
ATMOS_CONSUMER_SECRET=...
ATMOS_STORE_ID=...
ATMOS_PLATFORM_RECIPIENT_ID=...
ATMOS_OFD_CODE_RENTAL=      # optional: fiscal receipts are sent only when both are set
ATMOS_OFD_CODE_SERVICE=
```

- Spring reads it itself: `application.yaml` has
  `spring.config.import: optional:file:.env[.properties]`, so `.env` is loaded
  however the app is started — IntelliJ, `./mvnw spring-boot:run`, `java -jar` —
  as long as the working directory is the project root. No library is involved;
  spring-dotenv was removed because it does not hook into Spring Boot 4 and left
  `${JWT_SECRET}` unresolved.
- The file is parsed as a `.properties` file: `KEY=value`, one per line, `#` for
  comments. Don't wrap values in quotes (they would become part of the value) and
  don't use `export KEY=...`.
- **Real environment variables always win** over `.env`, so production sets them
  in the environment and needs no `.env` file at all (`optional:` means a missing
  file is fine).

## Profiles

`SPRING_PROFILES_ACTIVE` picks them; several can be active at once
(`prod,atmos`). Nothing is a profile-specific *file* except
`application-staging.yaml` — every other difference is a guard that reads the
active profiles at startup.

| Profile | What it is | Dev sign-in | Payments | Bot mode | Secrets |
|---|---|---|---|---|---|
| *(none)* | tests, a plain local run | no | `fake` | webhook | dev fallbacks allowed |
| `dev` | a developer's machine | **yes** (`DEV_LOGIN_KEY`, 16+) | `fake` | webhook or **polling** | dev fallbacks allowed |
| `staging` | a deployed **test** server | no | **`fake` allowed** | webhook or **polling** | **required** |
| `prod` | production | no | `fake` **refused** | webhook only | **required** |
| `atmos` | the real card provider, next to `prod` | no | `fake` never created | — | **required**, plus every `ATMOS_*` |

### The `staging` profile

A deployed test server: as strict as `prod` about real people and real secrets,
and loose about exactly the two things a test deployment cannot otherwise have —
a payment provider contract and a public webhook URL.

As strict as prod:

- **No dev-only sign-in.** `DevProfileGuard` refuses to start when `dev` is
  active together with `prod`, `staging` or `atmos`, so `/api/auth/dev/*` can
  never exist on a staging server.
- **No devtools.** Nothing profile-specific — the packaged jar leaves
  `spring-boot-devtools` out, and staging runs the jar.
- **Real secrets, or it does not start.** `CardTokenCipher` demands a real
  `CARD_TOKEN_KEY` here exactly as under `prod` (no fixed development key), and
  the required-settings check below refuses to start with any of them missing.
- **The real client IP.** `application-staging.yaml` sets
  `server.forward-headers-strategy: native`, so Tomcat's `RemoteIpValve` takes
  the client IP from `X-Forwarded-For` behind the platform's edge proxy. Without
  it every admin would share one 20-attempt sign-in budget and every audit entry
  would name the proxy. The valve trusts the header only from its internal-proxy
  ranges, which is where a platform proxy reaches the container from.
- **An https `MINIAPP_URL`.** `TelegramBotModeGuard` refuses a non-https Mini App
  URL here as under `prod` (Telegram refuses `web_app` buttons without it).

Loosened, on purpose:

- **`app.payments.provider=fake` is allowed.** `FakePaymentProvider` refuses only
  `prod` and `atmos`, so staging can walk a booking through payment end to end.
  No real money can move: no real provider is wired in, and the provider logs
  "no real money will move" on every start.
- **`TELEGRAM_BOT_MODE=polling` is allowed.** `TelegramBotModeGuard` accepts
  polling under `dev` **or** `staging`, so the bot works with no public webhook
  URL and no webhook secret (in webhook mode staging still requires the secret).
  Use a **test bot**: polling deletes that bot's webhook, and two processes must
  never poll the same bot — stop the local one while staging runs.

**`prod` keeps every refusal it has today.** None of the above relaxes it: under
`prod` the mode can only be `webhook`, so requiring the webhook secret in webhook
mode is the same rule as before.

## Deployment checklist

Go through this before the first production deploy and again whenever the
infrastructure changes. The app is built to run as **one instance behind Nginx**.
For the Railway test deployment (`staging`, no Docker, no Nginx) follow
`DEPLOYMENT.md` instead.

### Reverse proxy and client IPs

The admin sign-in limits (per-IP attempts, audit IPs) use `request.getRemoteAddr()`.
Behind a proxy that is the proxy's address unless Spring is told to read the
forwarded headers — and then every admin shares one 20-attempt budget.

- Set `server.forward-headers-strategy: native` (env `SERVER_FORWARD_HEADERS_STRATEGY=native`).
  Tomcat's `RemoteIpValve` then takes the client IP from `X-Forwarded-For` and the
  scheme from `X-Forwarded-Proto`.
- It only trusts those headers from **internal proxies** (by default loopback and the
  private ranges: 10/8, 172.16/12, 192.168/16, 127/8, ::1, …). If Nginx reaches the app from any other
  address, list it in `server.tomcat.remoteip.internal-proxies`, or the headers are
  ignored.
- Bind the app so nothing but Nginx can reach it (`server.address: 127.0.0.1`, or a
  private network / firewall). Anyone who can talk to the app directly can forge
  `X-Forwarded-For`.
- Nginx, as the edge, **replaces** the header instead of appending what the client
  sent:

  ```nginx
  location / {
      proxy_pass         http://127.0.0.1:8080;
      proxy_set_header   Host              $host;
      proxy_set_header   X-Forwarded-For   $remote_addr;
      proxy_set_header   X-Forwarded-Proto $scheme;
  }
  ```

  (Behind another proxy or a CDN, use `$proxy_add_x_forwarded_for` and add that
  hop to `internal-proxies` / Nginx `set_real_ip_from` instead.)

### HTTPS only

- Nginx terminates TLS (TLS 1.2+); the app itself speaks plain HTTP on localhost.
- Port 80 only redirects: `return 301 https://$host$request_uri;`.
- Send HSTS: `add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;`.
- Telegram requires HTTPS anyway, for the Mini App URL and for the bot webhook.
- JWTs and admin passwords travel in every request, so there is no "internal HTTP"
  exception, admin panel included.

### Secrets and configuration

- Real environment variables in production, no `.env` file (if one is used, it is
  `chmod 600` and owned by the service user). Real env vars beat `.env` anyway.
- `JWT_SECRET`: random, at least 32 bytes (`openssl rand -base64 48`), different
  from every other environment. Rotating it signs everyone out.
- `BOT_TOKEN`, `DB_PASSWORD`, `TELEGRAM_WEBHOOK_SECRET`: never in Git, Postman, logs or
  tickets. Under `prod` the app refuses to start without a valid webhook secret
  (32–256 of `A-Z a-z 0-9 _ -`, e.g. `openssl rand -hex 32`) and an https `MINIAPP_URL`.
  `TELEGRAM_BOT_MODE` stays `webhook` (polling refuses to start outside `dev`).
- `SPRING_PROFILES_ACTIVE=prod,atmos`. `FakePaymentProvider` refuses to start under
  `prod`, and the `atmos` profile wires `AtmosPaymentProvider`, which refuses to start
  without every `ATMOS_*` variable. Set `CARD_TOKEN_KEY` (`openssl rand -base64 32`)
  and keep it: losing it makes every saved card unusable. Each stadium owner's signed
  contract and ATMOS recipient id are entered by an admin
  (`PUT /api/admin/users/{id}/legal-details`) — a stadium cannot be approved before
  that. Settle the "Open ATMOS
  questions" (see "Payments") against the real API first.
- The Payme/Click webhooks are still mocks that trust the body without checking a
  signature (see "Deferred until the payment model is final"). Don't expose
  `/webhooks/payme` and `/webhooks/click` until they verify it — block them in
  Nginx otherwise.

### Build and run

- Run the packaged jar (`./mvnw clean package`, then `java -jar target/*.jar`),
  not `spring-boot:run`. The repackaged jar leaves out `spring-boot-devtools`,
  whose automatic restarts and dev defaults must not run in production.
- Run as an unprivileged service user under systemd (or a container) with
  `Restart=on-failure`.
- **Exactly one instance.** The `@Scheduled` jobs (booking expiry, game timeouts,
  reminders, series, promotions, completion) run on every instance, and
  `LoginRateLimiter` keeps its counts in memory. A second instance needs ShedLock
  (or similar) and a shared rate limiter first. (The bot's pending deep links,
  `StartPayloads`, are in memory too; its update dedupe is in the database and safe.)

### Database

- The app connects as a dedicated role that owns the `minipolya` database — not
  the `postgres` superuser. `btree_gist` and `pgcrypto` are trusted extensions, so
  the owner can create them during the Liquibase run.
- Liquibase applies pending changesets on startup; take a backup before deploying
  a release that contains new ones.
- Back up `UPLOADS_DIR` (stadium photos) together with the database: photo rows point
  at files there. Put it on persistent storage, not inside the release directory.
- Automated daily backups (`pg_dump` or PITR) and a restore you have actually tried.
- PostgreSQL listens only on localhost / the private network, with TLS if it is on
  another host.

### Application settings worth checking

- CORS is off by default (`CORS_ALLOWED_ORIGINS` empty): no CORS header is sent, so
  browsers only allow same-origin calls. Either serve the admin panel and the Mini App
  from the API's origin (via Nginx), or set `CORS_ALLOWED_ORIGINS` to exactly those
  origins, comma-separated (e.g. `https://admin.minipolya.uz,https://app.minipolya.uz`)
  — never `*`. It then covers `/api/**` (methods GET, POST, PUT, PATCH, DELETE,
  OPTIONS; headers Authorization, Content-Type; no credentials; preflights cached
  3600 s). Preflights pass without a JWT; other origins get 403; `/webhooks/**` never
  has CORS.
- Logging at `INFO`, `spring.jpa.show-sql: false` (already). Check that no log line
  carries tokens, card numbers or passwords before raising any level to DEBUG.
- `server.error.include-stacktrace` stays at its default (`never`);
  `GlobalExceptionHandler` already answers 500 with a generic message.
- Rate-limit `/api/auth/**` in Nginx too (`limit_req`) — a cheap outer layer in
  front of the in-app limits.

### After the first deploy

- Create the first admin with the SQL in "Admin accounts" and sign in. Passwords
  are changed only with the reset SQL there, never through the panel.
- Point the Telegram bot webhook at `https://<domain>/webhooks/telegram` **with the
  secret** (without it every update is refused with 403), and the Mini App URL at the
  HTTPS origin:

  ```bash
  curl -s "https://api.telegram.org/bot$BOT_TOKEN/setWebhook" \
    -d "url=https://<domain>/webhooks/telegram" \
    -d "secret_token=$TELEGRAM_WEBHOOK_SECRET" \
    -d 'allowed_updates=["message","callback_query"]'
  ```

  Run it from the server's shell (not a script in Git) so the token stays out of
  history; check with `getWebhookInfo`. The description, commands and menu button set
  themselves on startup.
- Smoke-test: sign in via Telegram, list stadiums, admin sign-in, and check that
  the audit log shows the real client IP and not Nginx's.

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

# Database-backed tests (@DatabaseTest: full context, Liquibase schema, MockMvc, each test
# rolled back) run against a separate database, TEST_DB_URL (default
# jdbc:postgresql://localhost:5432/minipolya_test) with DB_USERNAME/DB_PASSWORD; it is
# created on first use, and the tests are skipped when it cannot be reached. Never point
# TEST_DB_URL at the dev database. Note: a dev server running with devtools restarts on
# every compile and applies new changesets to the dev database straight away.

# Create database (Liquibase creates the tables, you just need the empty DB)
psql -U postgres -c "CREATE DATABASE minipolya;"

# Check Liquibase migration status
./mvnw liquibase:status

# Rollback last changeset (if needed)
./mvnw liquibase:rollbackCount -Dliquibase.rollbackCount=1
```

## Dependencies (pom.xml)

Required beyond Spring Initializr defaults:
- `org.liquibase:liquibase-core` — managed by Spring Boot (no version needed)
- `org.mapstruct:mapstruct:1.6.3` — MapStruct annotations
- `org.mapstruct:mapstruct-processor:1.6.3` — annotation processor (in `maven-compiler-plugin` `<annotationProcessorPaths>`)
- `org.projectlombok:lombok-mapstruct-binding:0.2.0` — required when using Lombok + MapStruct together
- `com.twelvemonkeys.imageio:imageio-webp:3.15.3` — a read-only ImageIO plugin for WebP stadium photos (see "Stadium photos")

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

## Deferred until the payment model is final

These were found during endpoint testing and left untouched on purpose — each one
depends on how payments, payouts and the platform's cut are finally modelled.

- **Owner revenue is defined two different ways.** `GET /api/owner/dashboard/stadiums/{id}/stats`
  sums only payments with `payment_type = booking`, so a confirmed game contributes 0 even though
  it produced a `source = game` booking on the pitch. `GET /api/owner/dashboard/stadiums/{id}/schedule`
  sums booking prices instead and therefore does include it. The same stadium reports two different
  revenue figures; the definition has to be decided before either is trusted.
- **Casual game share rounding loses so'm.** Each participant pays `total_price / max_players` with
  integer division, so the shares do not add up to the pitch price: 3 players on 130 000 collect
  129 999. `team_vs_team` no longer has this exposure — `TeamGameSplit` gives the odd tiyin to the
  second team, so its advances and remainders add up exactly.
- **Payment, webhook, refund and payout endpoints were not re-tested.** `POST /api/payments/initiate`,
  `GET /api/payments/my`, `GET /api/payments/{id}`, `POST /webhooks/payme`, `POST /webhooks/click` and
  the refund paths are still the mock implementations (`PaymeProvider` / `ClickProvider` return a fake
  checkout URL and the webhook trusts the body without verifying a provider signature).


