# Users, roles and auth

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching sign-in, JWTs, roles, profiles, admin accounts or endpoint security.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Users & Roles

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

## Auth

## Telegram Mini App (primary)
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

## Dev-only sign-in (`dev` profile)

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

## Telegram Login Widget (web dashboard)
- Same flow, different payload format
- Same JWT returned, same user record

## Admin accounts

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

## JWT
- Sent as `Authorization: Bearer <token>`
- Contains: userId, phone, isOwner, isAdmin
- Expiry: 24 hours
- `JwtAuthFilter` validates on every request, sets SecurityContext

## Endpoint Security
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
- `POST /webhooks/telegram` → no JWT; the `X-Telegram-Bot-Api-Secret-Token` header instead
  (see "Telegram bot"). It is the **only** public path under `/webhooks`: the unsigned
  Payme/Click callbacks were removed, and any other `/webhooks` path needs a JWT (401) and
  does not exist (404) — `LegacyCheckoutRemovedTest`.

