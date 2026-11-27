# Database and migrations

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching the schema, a Liquibase changeset, an entity or an id/money/timestamp type.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Database

- **Migrations**: Managed by Liquibase
  - Master changelog: `src/main/resources/db/changelog/db.changelog-master.yaml`
  - Changeset files: `src/main/resources/db/changelog/changes/*.sql` (55 files, 33 tables)
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

## Changeset Files

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
    ├── 052-game-team-payment-option.sql   # game_teams payment_option, join_price, remainder_price + remainder_mark / _marked_at / _marked_by
    ├── 053-stadium-weekly-hours.sql       # stadium_hours: one row per day of the week per stadium, backfilled from the stadiums' own open/close
    ├── 054-team-level.sql                 # teams.level: havaskor / orta / professional, nullable (teams from before have none)
    ├── 055-game-message-system.sql        # game_messages kind / event / event_team_name; user_id nullable for the platform's own lines
    └── 056-payment-needs-review.sql       # payments.status 'needs_review', payments.capture_requested_at; decline_reason 'WRONG_OTP'
```

## Adding New Migrations

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

