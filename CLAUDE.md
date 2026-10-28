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
    ├── CancellationPolicy.java
    ├── TeamGameSplit.java          # Fee / advance / remainder arithmetic for a team game
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
    └── 028-password-changed-at.sql   # users.password_changed_at — tokens issued earlier are refused
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
- Duration: any number of consecutive whole hours, minimum 1, no fixed maximum. The practical limit is the stadium's opening hours, closures, and existing bookings (a booking is a run of free consecutive 1-hour slots).
  - `startsAt` must be on the hour in `Asia/Tashkent` (minutes/seconds/nanos = 0) so bookings line up with the hourly availability slots.
  - The whole booking (`startsAt` → `startsAt + durationHours`) must fall within the stadium's `open_time`–`close_time` (Tashkent). `close_time` is treated as a same-day bound: a window where `close_time <= open_time` is unbookable (matches availability, which generates no slots for it), so bookings cannot cross midnight.
  - The booking must not overlap any `stadium_closure`.
  - Price = the sum of each 1-hour slot's price (per-hour pricing rule by local weekday/time, falling back to `stadium.price_per_hour`), with the best active promotion applied per hour — identical to what the availability endpoint shows for the same slots.
  - Violations throw `BadRequestException` → HTTP 400. A multi-hour overlap rejected by the DB EXCLUDE constraint reports "One or more of the selected hours is already booked"; a 1-hour overlap reports "Slot is already booked".
- Max booking: 4 weeks ahead
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
| `FULL` | the whole price | always |
| `MINIMUM` | `stadium.min_booking_amount` | the stadium has one **and** it is below this booking's price |

There are no other amounts. `min_booking_amount` is nullable — null means full
payment is required — and when set must be at least 50 000 tiyin (500 so'm, the
smallest payment ATMOS takes).

- `bookings.payment_status` is `unpaid` / `partially_paid` / `paid`, and
  `paid_amount` is the running total of successful payments. Responses carry
  `price`, `paidAmount` and `remainingAmount` (all tiyin).
- A successful charge confirms the booking. A **declined** card leaves it
  `pending` with a `failed` payment row, so the usual hold timeout expires it;
  the create call still answers 200 with `paymentStatus: unpaid`, and the
  message says the payment failed.
**Paying off the rest.** While a booking is `partially_paid` the remainder can be
settled two ways, and whichever lands first wins:

- `POST /api/bookings/{id}/pay-remainder` with a `cardId` — the player charges
  the whole remainder to a saved card, at any time, **before or after the game**.
  Paying after the game leaves the booking `completed`; it is never dragged back
  to `confirmed`.
- `POST /api/bookings/{id}/cash` — the owner marks the same money as received in
  cash (it takes `remainingAmount`, not the full price).

Once `payment_status` is `paid`, the other one answers **400 "Already paid"**.
The remainder is charged as a `remainder` payment row, and a declined card
leaves the booking `partially_paid` with a `failed` row.

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
- Booking responses carry **`cancellationDeadline`** and
  **`refundIfCancelledNow`** (tiyin). Both come from `CancellationPolicy` and the
  same refundable payments that a real cancellation uses, so the figure a player
  is shown is the figure they get. `BookingResponseFactory` fills them in.
- `cancellationPolicy` on a booking or stadium response is just
  `{ "cancellationHours": 24 }`.
- The default of **24 lives in exactly two places that agree**: the
  `stadiums.cancellation_hours` column default and the `Stadium` entity field.
  There is no `app.cancellation.*` config — don't reintroduce one.

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

*Money.* For stadium price `P`, stadium `min_booking_amount` `M` and
`platform_settings.team_game_platform_fee` `fee`, with `base = min(M, P)` (or `P`
when the stadium has no minimum) — all tiyin, see `util/TeamGameSplit.java`:

| Each team pays | Amount | When |
|---|---|---|
| platform fee | `fee` (25 000 so'm) | in the app, on joining |
| owner advance | `base / 2` | in the app, on joining |
| remainder | `(P − base) / 2` | later, in the app or in cash |

Where a halving is odd the extra tiyin goes to the **second** team, so the two
advances add up to exactly `base` and the two remainders to exactly `P − base`.
The ATMOS split of a joining payment is `ownerAmount = advance`,
`platformAmount = fee`; **no booking commission is applied to a team game**,
because the fee *is* the platform's cut, and a remainder is therefore all the
owner's (`platformAmount = 0`).

*The two deadlines.*
- **the deadline** — `starts_at − stadium.cancellation_hours` (default 24).
- **the 3-hour point** — `starts_at − app.game.opponent-deadline-hours`
  (default 3). After it nobody may join and a game still without an opponent is
  over. **team_vs_team only** — casual games still fill right up to kick-off.

*Creating (WAITING).* The creating leader picks a saved card; fee + advance go on
**HOLD** (`payments.status = held`, type `team_game`). No slot is booked. A
declined card means no game at all.

*Joining (→ CONFIRMED).* Order matters, and every failure leaves nobody paying
for a game that will not happen:
1. record the joining team — the partial unique index on `(game_id, side)`
   rejects a second opponent before any card is touched;
2. after the 3-hour point → 400 `O'yin boshlanishiga 3 soatdan kam qoldi, qo'shilib bo'lmaydi`;
3. book the slot (pending) — if the EXCLUDE constraint rejects it the joining
   team is **not** charged and nothing is compensated here on purpose: this
   transaction rolls back and a provider call would not, so whoever took the
   slot cancels this game and refunds its waiting team from their own
   committed transaction (`cancelGamesOnSlot`), with `GameTimeoutJob` as the
   backstop;
4. charge the joining team — a declined card removes the booking and the game
   goes on waiting;
5. capture the waiting team's hold, last, being the only step that cannot be
   undone cheaply; if it fails, both teams are made whole and the game ends;
6. confirm the booking and the game.

The joining team takes whichever side is **free**, not always B: when the
creating team walks out of a confirmed game the team left behind is on side B,
so the next opponent takes A. Confirming also clears any **other** team still
waiting on that hour, the same rule an ordinary booking follows — otherwise
their money would sit on hold for a pitch that is already gone.

`bookings.paid_amount` is the sum of the **advances** of the teams currently in
the game (plus any remainder already settled) — the fee is not money for the
pitch. So a confirmed game's booking is `partially_paid`, or `paid` outright when
the stadium has no minimum.

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
saved card at `POST /api/games/{id}/pay-remainder` (before or after the game);
the owner marks either team's as cash at `POST /api/games/{id}/cash` with
`{"side":"a"}`. Whichever lands first wins and the other answers **400 "Already
paid"** — the same answer a team gets when its advance already covered the pitch.
The booking reaches `paid` only once **both** teams currently in the game have
settled.

*What a team sees.* `GameDetailResponse.teams` carries, per team: `feeAmount`,
`advanceAmount`, `remainderAmount`, `remainderStatus`, `paidInApp`,
`cancellationDeadline` and `refundIfCancelledNow` (tiyin, 0 when nothing would
come back). The last one is computed by the same code that performs the
cancellation, so the figure shown is the figure paid. Note the asymmetry it
answers: past the 3-hour point a team's own figure is 0 because cancelling
forfeits, yet that same team is made whole if its *opponent* cancels.

*Finding opponents.* `GET /api/games/challenges` (filters `date`, `format`,
`stadiumId`; paged) lists WAITING team games before their 3-hour point, excluding
any the caller's own team is waiting on (membership and leadership both count).
Each item carries `amountToPayNow` = fee + the free side's advance. A team game
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
pay for the pitch.

**Common game rules:**
- `GameTimeoutJob` runs two cutoffs: team games whose **3-hour point** has
  passed, and every other type whose `starts_at` has passed
- When a regular booking takes the slot, find any waiting game on that
  stadium/time → cancel it `cancelled_slot_taken` and give everyone their money
  back (team games through `TeamGameService`, casual through participants)
- Game statuses: `open` → `confirmed` → `completed`
- Cancel statuses: `cancelled_slot_taken`, `cancelled_by_creator`, `cancelled_not_filled`
- `POST /api/games/{id}/pay` is the **casual** hosted checkout and refuses a team
  game: a team pays when creating or joining, from a saved card

### Platform Settings

Platform-wide money rules live in the single-row `platform_settings` table, not in
config. Admins read and change them at `GET` / `PUT /api/admin/settings`.

| Setting | Default | Meaning |
|---|---|---|
| `booking_commission_percent` | 0 | Percent of a booking price the platform keeps |
| `team_game_platform_fee` | 2 500 000 tiyin (25 000 so'm) | Charged per team on a team game |
| `casual_game_service_fee` | 0 | Charged per player on a casual game |

- The row is seeded by changeset 021; `singleton` is UNIQUE and CHECKed true, so a
  second row is impossible.
- Validation: commission 0–50, fees not negative. A rejected value writes nothing.
- Every change writes one `PLATFORM_SETTINGS_UPDATED` audit entry naming each
  changed field with its old and new value.

### Payments

**Provider.** ATMOS is the card provider. Every new flow goes through the
`PaymentProvider` interface in `provider/`: bind a card once
(`startCardBinding` → `confirmCardBinding` with the SMS code), then `charge`,
`hold`, `capture`, `release` and `refund` it by token. Amounts are tiyin.

- `FakePaymentProvider` stands in until the ATMOS docs land. It is active only
  when `app.payments.provider=fake` and **refuses to start under the `prod`
  profile**. Its SMS code is always `111111`, and a card number ending in `0002`
  is always declined so failure paths can be tested.
- A declined card comes back as a `FAILED` `ProviderTransaction`, not an
  exception. Malformed input (unknown binding or transaction, wrong SMS code,
  a hold settled twice) throws.
- **Refunds are always for the whole transaction** — ATMOS cannot refund part of
  one.
- `LegacyCheckoutProvider` (Payme/Click hosted checkout) is **legacy**. It now
  survives only for the casual game payment path (`POST /api/games/{id}/pay`),
  which refuses team games. Never use it in a new flow.

**Saved cards** (`saved_cards`): the provider token, a masked number
(`8600 •••• 1234`), card type and an active flag. The full card number is passed
straight to the provider and is **never stored or logged**; `AddCardRequest`
overrides `toString` to keep it out of stray log lines, and `SavedCardResponse`
has no token field at all. Deleting a card sets `is_active = false` so past
payments stay explainable. Endpoints: `POST /api/cards`,
`POST /api/cards/confirm`, `GET /api/cards`, `DELETE /api/cards/{id}`.

**Payment rows**

- `provider_txn_id` is UNIQUE — handles duplicate webhook calls (idempotent)
- Payment types: `booking`, `remainder`, `team_game`, `game_share`, `service_fee`, `refund`
  (`team_game` is one team's fee + advance; `remainder` is either a booking's or a
  team's outstanding balance; `game_share` is the legacy casual flow)
- `game_team_id` ties a team game's payments to the (game, team) that made them,
  so they survive the booking being cancelled and recreated when an opponent changes
- Amounts and the owner/platform split never change after a row is created
- Status only moves forward (`held` → `captured` or `released`)
- Refunds create a NEW payment row with negative amount and refund_reason; the
  original may be marked refunded but is never otherwise rewritten

### Availability Calculation

To show free slots for a stadium on a given date:
1. Get the stadium's `open_time` and `close_time`
2. Cut into 1-hour slots
3. Remove slots that overlap any `pending` or `confirmed` booking
4. Remove slots that fall within a `stadium_closure`
5. For each remaining slot, calculate price from `pricing_rules` (match day_of_week + time range)
6. Mark slots that have a WAITING **team_vs_team** game (show "a team is looking for an opponent" in UI). Casual games are private to whoever was invited and are never marked
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

### Admin accounts

Platform admins sign in to the admin web panel with a **username and password**,
not Telegram. They are a handful of people the platform owner creates directly in
the database. Players and stadium owners keep signing in through Telegram; nothing
changes for them. All of this lives in `service/AdminAuthService.java`.

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
- `POST /api/admin/me/password` with `{ "currentPassword", "newPassword" }`:
  a wrong current password → 400; the new one needs 10+ characters and at most
  72 bytes (BCrypt ignores the rest). Writes `ADMIN_PASSWORD_CHANGED`. It answers
  with a fresh `{ token, user }`, which the panel must swap in.
- **A password change signs out every older session.** It stamps
  `users.password_changed_at`, and `JwtAuthFilter` (which already loads the user
  for the blocked check) refuses any token issued before that with
  **401 `TOKEN_REVOKED`** — the caller's old token too, hence the fresh one. Tokens
  carry an `iatMs` claim (issue time in milliseconds) because the standard `iat` is
  whole seconds and could not tell apart a token issued in the same second as the
  change; a token without `iatMs` falls back to `iat`. Telegram-issued tokens of the
  same user are refused as well. Users with no `password_changed_at` are never checked.
- Limits are `app.admin-login.*` (`max-failed-attempts`, `lock-minutes`,
  `max-attempts-per-ip`, `ip-window-minutes`), defaults 5 / 15 / 20 / 15.
- Error responses here carry an extra `code` key in the envelope. It appears only
  when set, so every other response keeps its three keys.

**Passwords are never stored or logged in plain text.** Only the BCrypt hash goes
into `users.password_hash`; audit entries and log lines carry the username and IP,
never a password; `AdminLoginRequest` and `ChangePasswordRequest` override
`toString` to mask them. The SQL below necessarily contains the plain password
in the statement itself: don't save it in a script file or in `psql` history
(`\set HISTFILE /dev/null` before running it, or clear the history afterwards),
and have the admin change it from the panel after the first sign-in.

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
  - `app.game.opponent-deadline-hours` (default 3) is the team-game joining
    cutoff. Money rules live in `platform_settings`, not here — only timing does.
  - `app.admin-login.*` holds the admin password sign-in limits (see "Admin accounts").
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

## Deployment checklist

Go through this before the first production deploy and again whenever the
infrastructure changes. The app is built to run as **one instance behind Nginx**.

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
- `BOT_TOKEN`, `DB_PASSWORD`: never in Git, Postman, logs or tickets.
- `SPRING_PROFILES_ACTIVE=prod`. `FakePaymentProvider` refuses to start under it,
  so **a real payment provider (ATMOS) must be wired before production** —
  `PAYMENTS_PROVIDER=fake` will not boot.
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
  (or similar) and a shared rate limiter first.

### Database

- The app connects as a dedicated role that owns the `minipolya` database — not
  the `postgres` superuser. `btree_gist` and `pgcrypto` are trusted extensions, so
  the owner can create them during the Liquibase run.
- Liquibase applies pending changesets on startup; take a backup before deploying
  a release that contains new ones.
- Automated daily backups (`pg_dump` or PITR) and a restore you have actually tried.
- PostgreSQL listens only on localhost / the private network, with TLS if it is on
  another host.

### Application settings worth checking

- CORS: none is configured, so browsers only allow same-origin calls. Serve the
  admin panel and the Mini App from the API's origin (via Nginx), or add a CORS
  config that allows exactly those origins — never `*` with credentials.
- Logging at `INFO`, `spring.jpa.show-sql: false` (already). Check that no log line
  carries tokens, card numbers or passwords before raising any level to DEBUG.
- `server.error.include-stacktrace` stays at its default (`never`);
  `GlobalExceptionHandler` already answers 500 with a generic message.
- Rate-limit `/api/auth/**` in Nginx too (`limit_req`) — a cheap outer layer in
  front of the in-app limits.

### After the first deploy

- Create the first admin with the SQL in "Admin accounts", sign in, and change the
  password from the panel (that also revokes the session made with the SQL one).
- Point the Telegram bot webhook at `https://<domain>/webhooks/telegram` and the
  Mini App URL at the HTTPS origin.
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


