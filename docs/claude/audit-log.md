# Audit log

> Part of the MiniPolya CLAUDE.md doc set. **Read this before adding or changing an action that writes data, or reading/filtering the audit log.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Audit log

The admin panel shows **who did what and exactly what changed**. Every action that
changes data writes one entry, from admins, owners, players and the system alike.
The code lives in `audit/` (`AuditEntry`, `AuditAction`, `AuditChange`,
`AuditLabels`, `AuditEntityType`) and `service/AuditService.java`.

## What an entry records

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

## Writing an entry

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

## What is never logged

**Passwords, password hashes, card numbers, card tokens (`providerToken`), JWTs, the
bot token or any other secret** — not as a field, not in a label, not in a reason.
`AuditEntry.change` drops any field whose name looks like one (password, hash, token,
card number, cvv, secret, jwt) and logs an error, but that is a safety net, not
permission: never pass such a value in the first place. A walk-in client's phone
number is not logged either; their name is part of the booking label.

## Logged actions

| Area | Actions |
|---|---|
| Admin | `ADMIN_LOGIN_SUCCEEDED`, `ADMIN_LOGIN_FAILED`, `APPLICATION_APPROVED`, `APPLICATION_REJECTED`, `STADIUM_APPROVED`, `STADIUM_REJECTED` (pending → rejected; before changeset 038, pending → blocked), `STADIUM_BLOCKED`, `STADIUM_UNBLOCKED` (blocked → approved), `STADIUM_STATUS_CHANGED` (back to pending) — each with `status` and `statusReason` changes, `STADIUM_NOTE_ADDED` (the note's id, never its text), `STADIUM_BOOKING_TERMS_CHANGED` (`minBookingAmount`, `cancellationHours`), `STADIUM_HOURS_CHANGED` (the week before → after, as one `hours` line), `USER_BLOCKED`, `USER_UNBLOCKED`, `USER_ROLE_CHANGED`, `OWNER_LEGAL_DETAILS_CHANGED`, `REFUND_RETRIED`, `REFUND_MARKED_MANUAL`, `PAYMENT_REVIEW_RESOLVED` (a `needs_review` hold settled by hand: `status`, the admin's reason), `SETTINGS_CHANGED` |
| Owner | `STADIUM_CREATED`, `STADIUM_UPDATED` (incl. `pricePerHour`, `landmark`, photos, amenities — the four of changeset 048 too; rows from before the rework may carry `minBookingAmount` / `cancellationHours`), `PRICING_RULE_CREATED` / `_UPDATED` / `_DELETED`, `CLOSURE_CREATED` / `_DELETED`, `PROMOTION_CREATED` / `_DEACTIVATED`, `MANUAL_BOOKING_CREATED`, `CASH_RECEIVED` (a booking — with `remainderMark` — or one team's game remainder, with `teamX.remainderMark`), `REMAINDER_NOT_PAID` (a booking or a team), `REMAINDER_MARK_UNDONE` (also ADMIN, for the admin's undo), `BOOKING_CANCELLED_BY_OWNER` |
| Player | `BOOKING_CREATED`, `BOOKING_CANCELLED_BY_USER`, `BOOKING_REMAINDER_PAID`, `BOOKING_PAYMENT_RETRIED`, `BOOKING_SERIES_CREATED` / `_CANCELLED`, `GAME_CREATED`, `GAME_JOINED`, `GAME_LEFT`, `GAME_CANCELLED`, `GAME_OPPONENT_LEFT`, `GAME_REMAINDER_PAID`, `TEAM_LEVEL_CHANGED` (the leader changed how seriously the team plays; entity type `team`, label its name, `level` old → new — and nothing at all when the level is unchanged), `PROFILE_UPDATED` (also a language picked in the bot), `PHONE_SHARED_VIA_BOT` (`phoneChanged`, never the number) |
| System | `BOOKING_CONFIRMED` (a payment confirmed it), `BOOKING_EXPIRED`, `BOOKING_COMPLETED`, `BOOKING_CANCELLED_BY_OWNER` (by a stadium block), `GAME_CONFIRMED`, `GAME_AUTO_CANCELLED`, `PAYMENT_COMPLETED`, `PAYMENT_RELEASED`, `PAYMENT_NEEDS_REVIEW` (a hold's capture or give-back had no clear outcome; the reason says which), `REFUND_CREATED`, `REFUND_FAILED` (ATMOS declined; the reason is its error), `PROMOTION_EXPIRED` |

`BOOKING_CREATED` records both prices: `price` is the **owner's** price for the hour and
`cardPrice` is what the player's card is actually charged (`PriceCalculator.playerAmount`
of it, and what the booking's own `card_price` column holds). An admin reading the entry
needs the charged figure; `price` alone was read as the amount paid, which on a card
booking it never was. `MANUAL_BOOKING_CREATED` has only `price`: a walk-in is cash, and
cash carries no card fee.

Codes are **stable**: they are stored and filtered on, so never rename one — add a
new constant. Legacy rows may carry older codes (e.g. `PLATFORM_SETTINGS_UPDATED`,
`GAME_CANCELLED` written by the system); the action filter still accepts them.

## Reading it: admin endpoints

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

