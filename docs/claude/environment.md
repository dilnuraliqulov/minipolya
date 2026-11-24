# Environment variables and profiles

> Part of the MiniPolya CLAUDE.md doc set. **Read this before adding a setting, reading `.env`, or changing what a profile allows.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

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
  file is fine). `.env` is git-ignored and never deployed.

## A managed database's own variables

A hosted PostgreSQL does not describe itself the way Spring wants. It publishes
either one URL — `DATABASE_URL=postgresql://user:password@host:5432/database`,
which is **not** a JDBC URL — or the libpq variables `PGHOST`, `PGPORT`,
`PGDATABASE`, `PGUSER`, `PGPASSWORD`.

`config/env/HostDatabaseEnvironmentPostProcessor` reads both and fills in
whichever of `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` is not set directly, so
there are three equally good ways to configure the database and the rest of the
app still sees only those three names:

1. set them yourself — what the Docker Compose deployment does, pointing `DB_URL`
   at the `postgres` service on the container network
   (`jdbc:postgresql://postgres:5432/minipolya`);
2. set `DATABASE_URL` alone — what a managed database hands out;
3. set the five `PG*` variables.

What it derives is added as the **lowest**-precedence property source, so a value
set directly always wins. It never guesses a host or a database name: with none of
those variables present it adds nothing and the check below speaks up.

## Required settings on a deployed server

`config/env/RequiredEnvironmentPostProcessor` refuses to start — before any bean
exists — when a deployment profile (`staging`, `prod`, `atmos`) is active and a
required setting is missing, naming **all** of them at once with what each one is:

```
The staging profile needs 2 settings that are not set:

  JWT_SECRET
      at least 32 bytes of random text (openssl rand -base64 48), different from …
  UPLOADS_DIR
      an absolute path on a mounted volume, e.g. /data/uploads. The default …
```

Required everywhere: `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (after the derivation
above), `JWT_SECRET`, `BOT_TOKEN`, `CARD_TOKEN_KEY`. `staging` additionally
requires `UPLOADS_DIR`, because the default `./uploads` is inside the container and
every redeploy would take the photos with it while their rows stayed in the
database. Nothing is checked under `dev` or in the tests, which take their values
from `.env` or from test properties.

Without this the first missing value surfaced as `Could not resolve placeholder
'DB_URL'` inside a long stack trace, one variable per restart, and a missing
`CARD_TOKEN_KEY` was found only when the first card was saved.

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

## The `staging` profile

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

