# Railway test deployment

How to put MiniPolya on [Railway](https://railway.com) as a **test** deployment, by
clicking in the dashboard. No Docker: Railway's own builder (Railpack) detects Maven in the
backend repo and Node in the frontend repo.

This file is **the same in both repositories** (`minipolya` and `minipolya-frontend`); edit
both or neither.

> **This is a test deployment, not production.** Fake payments (no real money can move),
> the Telegram bot on polling with a **test** bot, no custom domain, no webhook, no
> backups. What production would need instead is at the end, under
> "Later, for production".

## Contents

1. [What you end up with](#1-what-you-end-up-with)
2. [Before you start](#2-before-you-start)
3. [Create the project and the database](#3-create-the-project-and-the-database)
4. [Create the backend service](#4-create-the-backend-service)
5. [Create the frontend service](#5-create-the-frontend-service)
6. [Every environment variable](#6-every-environment-variable)
7. [First deploy](#7-first-deploy)
8. [After the first deploy](#8-after-the-first-deploy)
9. [Test checklist](#9-test-checklist)
10. [One bot, one poller](#10-one-bot-one-poller)
11. [Logs, redeploy, roll back, cost](#11-logs-redeploy-roll-back-cost)
12. [Troubleshooting](#12-troubleshooting)
13. [Later, for production](#13-later-for-production)

---

## 1. What you end up with

One Railway project, one environment, three services:

```
                  internet
                     │
                     │  https://<something>.up.railway.app
                     ▼
       ┌───────────────────────────────┐
       │ frontend  (Node, vite preview)│   ONE public domain
       │   /         → Mini App        │
       │   /admin    → admin panel     │
       │   /api/**   → forwarded ──────┼──┐  private network, http://
       └───────────────────────────────┘  │  (photos too: /api/uploads/**)
                                          ▼
       ┌───────────────────────────────┐  ┌──────────────┐
       │ backend  (Java 21, one jar)   │─▶│ Postgres     │
       │   NO public domain            │  │ (Railway)    │
       │   volume /data (uploads)      │  └──────────────┘
       └───────────────────────────────┘
```

- The **backend has no public domain**. Nothing reaches it but the frontend service, over
  Railway's private network. That is also why no CORS is involved anywhere: every call the
  browser makes goes to its own origin.
- The **frontend has one domain**, and serves both apps from it — the Mini App at `/`, the
  admin panel at `/admin`.
- Uploaded stadium photos are stored on a **volume** and served through the same `/api`
  path, so they survive redeploys and load from the one domain.

## 2. Before you start

- A Railway account with a plan that allows volumes and private networking (the Hobby plan
  does).
- Both repositories pushed to GitHub, each with the branch you want to deploy:
  - backend: `https://github.com/dilnuraliqulov/minipolya`
  - frontend: `https://github.com/dilnuraliqulov/minipolya-frontend`
  - **branch: `feature/railway-test`** in both. (Change it below if you deploy another one.)
- A **second, test** Telegram bot from [@BotFather](https://t.me/BotFather) — never the
  production bot. See [One bot, one poller](#10-one-bot-one-poller).
- Three secrets generated on your own machine (keep them somewhere safe, they are not
  recoverable from Railway later):

  ```bash
  openssl rand -base64 48   # JWT_SECRET      — signs every token
  openssl rand -base64 32   # CARD_TOKEN_KEY  — must decode to exactly 32 bytes
  ```

  `CARD_TOKEN_KEY` encrypts saved card tokens. Losing it makes every card saved on this
  deployment unreadable, so write it down even for a test server.
- A Yandex Maps JavaScript API v3 key (the Mini App's maps). Without one the Mini App falls
  back to Leaflet + OpenStreetMap and still works.

## 3. Create the project and the database

1. **New project.** Railway dashboard → **New Project** → **Empty project**. Name it, e.g.
   `minipolya-test`.
2. **Add Postgres.** In the project canvas → **Create** (or **+ New**) → **Database** →
   **Add PostgreSQL**. It comes up as a service named **`Postgres`** — the name matters,
   because the variable references below use it. If you name it something else, use that
   name in `${{...}}` everywhere below.
3. Open the Postgres service → **Variables** and check the names it publishes. You should
   see `DATABASE_URL`, `PGHOST`, `PGPORT`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` (and
   `DATABASE_PUBLIC_URL`, which you will not use). **Do not copy the values** — the
   references below pull them in and keep working when Railway rotates them.

Nothing else to do here. The backend creates every table itself: Liquibase applies its
changesets on startup, and the two extensions it needs (`btree_gist`, `pgcrypto`) are
trusted extensions the database owner may create.

## 4. Create the backend service

### 4.1 Create it from GitHub

1. Project canvas → **Create** → **GitHub Repo** → `dilnuraliqulov/minipolya`.
   (First time: **Configure GitHub App** and give Railway access to the repo.)
2. Rename the service to **`backend`** — service settings → **Service Name**. The name
   becomes its private hostname, `backend.railway.internal`, which the frontend points at.
3. It may start building straight away and fail. That is fine; it has no variables yet.
   Finish the settings below first.

### 4.2 Settings → Source

| Field | Value |
|---|---|
| Repository | `dilnuraliqulov/minipolya` |
| Branch | `feature/railway-test` |
| Root Directory | *leave empty* (the repo root is the Maven project) |
| Wait for CI | off |

### 4.3 Settings → Build

| Field | Value |
|---|---|
| Builder | **Railpack** |
| Build Command | `./mvnw -B -ntp -DskipTests clean package` |
| Watch Paths | *leave empty* |

- **Java 21** is pinned with the variable `RAILPACK_JDK_VERSION=21` (section 6). Railpack's
  documented order is: `RAILPACK_JDK_VERSION`, then Java 8 for Gradle ≤ 5, then a default of
  21. Setting it explicitly means a change to that default cannot move the app off the
  version `pom.xml` compiles for (`<java.version>21</java.version>`).
- `-DskipTests` on purpose: the tests need a database of their own, and they are what you
  run locally (`./mvnw test`) before pushing. Railway builds the jar, it does not test it.
- The build produces **one runnable jar** at a fixed path, `target/minipolya-backend.jar`
  (`<finalName>` in `pom.xml`), so the start command never has to know the version.

### 4.4 Settings → Deploy

| Field | Value |
|---|---|
| Start Command | `java -XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -jar target/minipolya-backend.jar` |
| Healthcheck Path | `/actuator/health` |
| Healthcheck Timeout | `300` |
| Restart Policy | **On Failure** |
| Restart Policy Max Retries | `10` |
| Replicas | **1** |

- `-XX:MaxRAMPercentage=75` lets the JVM use three quarters of the container's memory as
  heap; the default (25% on small containers) wastes most of it.
  `-XX:+ExitOnOutOfMemoryError` makes the JVM die on an out-of-memory error instead of
  limping on, so the restart policy can do its job.
- `/actuator/health` answers `{"status":"UP"}` without credentials, and is the only actuator
  endpoint exposed. Railway's healthcheck uses the `PORT` value, which is why `PORT` is set
  explicitly in section 6.
- **Exactly one replica, and this is not negotiable.** The scheduled jobs (booking expiry,
  game timeouts, reminders, series, promotions, payment reconcile) run on every instance,
  the admin sign-in rate limiter counts in memory, and only one process may poll a Telegram
  bot. A second replica would double-run the jobs and fight over the bot.

### 4.5 Settings → Networking

- **Public Networking: do not generate a domain.** If one was generated automatically,
  remove it. The backend is reachable only over the private network.
- **Private Networking** needs nothing switched on. The service answers at
  `backend.railway.internal` on the port it listens on. Internal traffic is `http://`, not
  `https://` — it is already encrypted between services.

### 4.6 Settings → Volumes (the uploads)

1. Service settings → **Volumes** → **Add Volume** (or right-click the service on the canvas
   → **Attach Volume**).
2. **Mount path: `/data`**.
3. Size: the smallest offered is plenty for a test.

`UPLOADS_DIR=/data/uploads` (section 6) puts stadium photos there. The app creates the
directory itself on the first upload.

Why it matters: a photo row in the database points at a file on disk. Without a volume the
files live inside the container, so **every redeploy would wipe the photos** while their
rows stayed in the database — stadiums with broken images and no way back. The app refuses
to start under the `staging` profile without `UPLOADS_DIR` for exactly this reason.

Note: a service with a volume cannot have two deployments live at once, so a redeploy of the
backend has a few seconds of downtime even with a healthcheck. That is expected.

## 5. Create the frontend service

### 5.1 Create it from GitHub

1. Project canvas → **Create** → **GitHub Repo** → `dilnuraliqulov/minipolya-frontend`.
2. Rename the service to **`frontend`**.

### 5.2 Settings → Source

| Field | Value |
|---|---|
| Repository | `dilnuraliqulov/minipolya-frontend` |
| Branch | `feature/railway-test` |
| Root Directory | *leave empty* |

### 5.3 Settings → Build

| Field | Value |
|---|---|
| Builder | **Railpack** |
| Build Command | `npm ci && npm run build` |

`npm ci` installs exactly what `package-lock.json` says. `npm run build` type-checks
(`tsc -b`) and builds both apps into `dist/` — `index.html` for the Mini App and
`admin.html` for the admin panel.

**Every `VITE_*` variable is read during this build**, not at run time: Vite writes their
values into the bundles. Change one and you must **redeploy**, not restart.

### 5.4 Settings → Deploy

| Field | Value |
|---|---|
| Start Command | `npm start` |
| Healthcheck Path | `/` |
| Healthcheck Timeout | `300` |
| Restart Policy | **On Failure** |
| Restart Policy Max Retries | `10` |
| Replicas | **1** |

`npm start` runs `scripts/start-preview.mjs`, which serves `dist/` with Vite's preview
server on `0.0.0.0` and Railway's `$PORT`. It is a small Node script rather than
`vite preview --host 0.0.0.0 --port $PORT` so that the same command works on Windows, where
the shell does not expand `$PORT`. `npm run preview` still serves on localhost for local
checks.

### 5.5 Settings → Networking

- **Public Networking → Generate Domain.** You get something like
  `minipolya-frontend-production-1a2b.up.railway.app`. **This is the one public address of
  the whole deployment.** Write it down; several steps below need it.
- `vite.config.ts` allows `.up.railway.app` by name (`preview.allowedHosts`), so the
  generated domain is answered and any other `Host` header gets a 403. It also allows
  `healthcheck.railway.app`, which is where Railway's own healthcheck requests come from —
  without it the healthcheck would get that 403 and the deployment would never go live.
  **A custom domain would have to be added to that list** (and the frontend redeployed)
  before it answered anything.
- No custom domain for a test deployment.

### 5.6 What the frontend serves

| URL | What |
|---|---|
| `https://<domain>/` | the Telegram Mini App |
| `https://<domain>/admin` | the admin panel (deep links like `/admin/stadiums/<id>` work) |
| `https://<domain>/api/**` | forwarded to `backend.railway.internal` |
| `https://<domain>/api/uploads/**` | uploaded stadium photos, through the same forward |

Every response carries a Content-Security-Policy that allows only what the apps use
(Telegram's script, the Yandex Maps API and its tiles, OpenStreetMap tiles for the fallback
map, our own origin), `frame-ancestors` for Telegram's web clients only, HSTS, `nosniff`
and `Referrer-Policy: strict-origin-when-cross-origin`. The HTML is never cached, so a
redeploy is picked up immediately.

## 6. Every environment variable

Set these under each service → **Variables** → **New Variable** (or **Raw Editor** to paste
several at once).

**Secret** means: never put it in a committed file, a screenshot, a ticket or a chat.
Railway hides variable values until you click to reveal them; that is where secrets live.

**Phase** means when the value is used: a **build**-time value is read while the image is
built (so changing it needs a *redeploy*), a **runtime** value is read when the process
starts (a *restart* is enough).

### 6.1 Backend service (`backend`)

| Variable | Example / value | Secret | Where it comes from | Phase |
|---|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `staging` | no | this document — the profile that allows fake payments and bot polling while keeping prod's safety | runtime |
| `RAILPACK_JDK_VERSION` | `21` | no | pins the builder's JDK to the version `pom.xml` compiles for | build |
| `PORT` | `8080` | no | set it explicitly, see note below | runtime |
| `SERVER_ADDRESS` | `::` | no | this document — binds every interface, IPv6 and IPv4 | runtime |
| `DB_URL` | `jdbc:postgresql://${{Postgres.PGHOST}}:${{Postgres.PGPORT}}/${{Postgres.PGDATABASE}}` | no | typed exactly like that: a Railway variable reference | runtime |
| `DB_USERNAME` | `${{Postgres.PGUSER}}` | no | reference | runtime |
| `DB_PASSWORD` | `${{Postgres.PGPASSWORD}}` | **yes** | reference (Railway keeps the value) | runtime |
| `JWT_SECRET` | 48 random bytes, base64 | **yes** | `openssl rand -base64 48` on your machine | runtime |
| `CARD_TOKEN_KEY` | 32 random bytes, base64 | **yes** | `openssl rand -base64 32` — must decode to exactly 32 bytes | runtime |
| `BOT_TOKEN` | `8123456789:AAE…` | **yes** | @BotFather, your **test** bot | runtime |
| `BOT_USERNAME` | `minipolya_test_bot` | no | @BotFather, without the `@` | runtime |
| `TELEGRAM_BOT_MODE` | `polling` | no | this document — no public URL needed, so no webhook | runtime |
| `MINIAPP_URL` | `https://<frontend domain>` | no | the frontend's generated domain (section 5.5); must be `https`, no trailing slash | runtime |
| `SUPPORT_USERNAME` | `minipolya_support` | no | optional: a public Telegram username, without `@`, that the bot shows | runtime |
| `UPLOADS_DIR` | `/data/uploads` | no | this document — must be under the volume's mount path | runtime |

**About `PORT`.** Railway injects a `PORT` variable and uses its value for the healthcheck.
Set it to `8080` yourself anyway, because the frontend has to name a **known** port in
`BACKEND_INTERNAL_URL`: with the port left to the platform, the private address could point
at the wrong one after a redeploy. `8080` is also the app's own default, so nothing is
surprising in the logs.

**About `SERVER_ADDRESS=::`.** Railway environments created after 16 October 2025 resolve
`*.railway.internal` to both IPv4 and IPv6; older ones resolve to IPv6 only. A socket bound
to `::` on Linux accepts both, so this one value works in either case. Leave it out and the
app binds IPv4 only (`0.0.0.0`), which an IPv6-only private network cannot reach.

**Variables you must NOT set on the backend:**

| Variable | Why not |
|---|---|
| `DEV_LOGIN_KEY` | the dev-only sign-in. The app **refuses to start** if the `dev` profile is active next to `staging`, so it cannot be turned on here at all. |
| `PAYMENTS_PROVIDER` | leave it unset: the default `fake` is what a test deployment wants. |
| `ATMOS_*` | real card payments. Not for a test deployment. |
| `TELEGRAM_WEBHOOK_SECRET` | nothing registers a webhook in polling mode. |
| `CORS_ALLOWED_ORIGINS` | leave it **empty**. The browser only ever calls the frontend's own origin, so no CORS header should be sent at all. |

**One-variable alternative for the database.** Instead of the three `DB_*` variables you may
set a single `DATABASE_URL=${{Postgres.DATABASE_URL}}`. The app converts that
(`postgresql://user:password@host:port/database`) into a JDBC URL, a username and a password
itself, and the five `PG*` variables work the same way. Anything you set directly always
wins. The three explicit variables are listed above because they are what the app's own
documentation names.

### 6.2 Frontend service (`frontend`)

| Variable | Example / value | Secret | Where it comes from | Phase |
|---|---|---|---|---|
| `BACKEND_INTERNAL_URL` | `http://backend.railway.internal:8080` | no | the backend service's name + its `PORT`. `http`, not `https` | runtime |
| `VITE_API_URL` | *(leave empty, or do not create it)* | no | empty means "my own origin", which is what the `/api` forward above is for | build |
| `VITE_YANDEX_MAPS_API_KEY` | `a1b2c3d4-…` | no (public, but restrict it by Referer) | Yandex developer cabinet | build |
| `VITE_TELEGRAM_BOT_USERNAME` | `minipolya_test_bot` | no | @BotFather, without `@` | build |
| `VITE_TELEGRAM_APP_NAME` | `app` | no | the Mini App's **short name** from @BotFather | build |
| `VITE_TELEGRAM_SUPPORT_USERNAME` | `minipolya_support` | no | optional, same value as the backend's `SUPPORT_USERNAME` | build |
| `PORT` | *(do not set)* | no | Railway injects it; `npm start` listens on it | runtime |

**Do NOT set `VITE_DEV_LOGIN_KEY`** on the frontend. The dev sign-in picker is compiled out
of a production build entirely (it lives behind `import.meta.env.DEV`), so the variable
would do nothing — and the backend refuses the dev profile here anyway. You can confirm a
build is clean:

```bash
npm run build
grep -ril 'auth/dev\|X-Dev-Login-Key\|DevLoginPicker\|VITE_DEV_LOGIN_KEY' dist/   # no matches
```

## 7. First deploy

1. Set every variable from section 6 on both services. Railway queues a redeploy each time
   you save; let it settle.
2. **Deploy the backend first** (it creates the database schema). Service → **Deploy**, or
   the **Deploy** button at the top of the project.
3. Watch **Deploy Logs**. A healthy start ends with something like:

   ```
   Liquibase: Update has been successful
   Tomcat started on port 8080 (http)
   Started MinipolyaBackendApplication in 12.3 seconds
   FakePaymentProvider is active (app.payments.provider=fake): no real money will move
   ```

   That last warning is expected here, and is exactly what you want to see on a test
   server: no real money can move.
   - If a required variable is missing, the log says so in one block, naming **all** of
     them with what each one is, and the app stops. Add them and redeploy.
4. The healthcheck has to go green before the deployment becomes active. If it times out,
   see [Troubleshooting](#12-troubleshooting).
5. **Then deploy the frontend.** Its log ends with the preview server's address.
6. Open `https://<frontend domain>/` in a normal browser. The Mini App loads and says it
   must be opened from Telegram — that is correct: it has no Telegram `initData` outside
   Telegram, and there is no dev sign-in on this deployment.

## 8. After the first deploy

### 8.1 Create the first admin user

The admin panel signs in with a username and password, and **passwords exist only in the
database** — there is no API, and no screen, that creates or changes one. So the first admin
is created with SQL.

1. Railway → the **Postgres** service → the **Data** tab (or **Query**) — Railway's own
   Postgres console.
2. Run, with your own values:

   ```sql
   INSERT INTO users (username, name, password_hash, is_admin)
   VALUES ('dilnur', 'Dilnur Aliqulov', crypt('a-long-unique-password', gen_salt('bf', 12)), true);
   ```

   `crypt` and `gen_salt` come from `pgcrypto`, which the backend's changeset 027 already
   enabled — so run this **after** the backend's first successful deploy, or the functions
   will not exist yet.
3. The password must be at least 10 characters (BCrypt ignores anything past 72 bytes). Use
   a different one from production.
4. Sign in at `https://<frontend domain>/admin`.

**The statement contains the password in clear text.** Do not save it in a file, do not
commit it, and clear it from the console's history afterwards. Hand the password over
privately.

Resetting a password (also clears any lockout, and signs out every existing session):

```sql
UPDATE users
SET password_hash         = crypt('a-new-long-password', gen_salt('bf', 12)),
    password_changed_at   = now(),
    failed_login_attempts = 0,
    locked_until          = NULL
WHERE LOWER(username) = LOWER('dilnur');
```

Unlocking an account after five wrong passwords, without changing it:

```sql
UPDATE users SET locked_until = NULL, failed_login_attempts = 0
WHERE LOWER(username) = LOWER('dilnur');
```

### 8.2 @BotFather: point the test bot at the deployment

With your **test** bot selected in [@BotFather](https://t.me/BotFather):

1. **Create the Mini App** (if you have not): `/newapp` → choose the bot → title, short
   description, a 640×360 photo → **Web App URL: `https://<frontend domain>`** → short name.
   That short name is the frontend's `VITE_TELEGRAM_APP_NAME`, and the Mini App link is
   `https://t.me/<bot>/<short name>`.
2. **Menu button:** `/mybots` → the bot → **Bot Settings** → **Menu Button** → **Edit menu
   button URL** → `https://<frontend domain>`.
3. If the Mini App already exists, `/myapps` → the app → **Edit Web App URL** →
   `https://<frontend domain>`.

The bot's description, commands and menu button are also set by the backend itself on
startup (`TelegramBotSetup`), from `MINIAPP_URL`. It reads each value first and writes only
what differs, so a restart changes nothing at Telegram.

### 8.3 `MINIAPP_URL` on the backend

Set the backend's `MINIAPP_URL` to `https://<frontend domain>` (no trailing slash) and
restart it. Telegram refuses a `web_app` button whose URL is not `https`, and under
`staging` the app refuses to start with a non-https value, so this is not optional.

### 8.4 The Yandex Maps key's allowed Referer

In the Yandex developer cabinet, for the Maps JavaScript API key, add the deployment to the
allowed HTTP Referers:

- `*.up.railway.app` (or exactly `<frontend domain>`, which is tighter)
- keep `localhost` for development

Without it the Yandex script refuses to load and the Mini App silently falls back to Leaflet
+ OpenStreetMap. `Referrer-Policy: strict-origin-when-cross-origin` is what makes sure the
origin still reaches Yandex in the `Referer` header; a stricter policy would break the key.

The free key allows about 100 requests a day, which is why the stadium page shows a static
location card and only loads the map after "Xaritani koʻrsatish".

## 9. Test checklist

Open the Mini App **from Telegram** (`https://t.me/<test bot>/<short name>`, or the bot's
menu button) on a phone or in Telegram Web.

**The bot**

- [ ] `/start` → the banner photo, the greeting, and the "⚽ MiniPolya'ni ochish" button
- [ ] the phone-number request appears, and sending the contact is accepted
- [ ] `/help` and `/language` answer; switching the language changes later messages
- [ ] the menu button opens the Mini App

**The Mini App**

- [ ] it opens from Telegram and signs in by itself (no login screen)
- [ ] the stadium list loads with real data
- [ ] a stadium page shows its photos (they come through `/api/uploads/**`) and the location
      card; "Xaritani koʻrsatish" loads the Yandex map
- [ ] availability for a day loads, and prices show as `… soʻm`
- [ ] a booking can be created and paid with a **fake** card — any 16-digit `8600…` or
      `9860…` number, a future MM/YY, and SMS code `111111`
- [ ] the booking appears under "Bandlovlarim" with the right status
- [ ] cancelling shows the refund the API returned

**The admin panel**

- [ ] `https://<frontend domain>/admin` shows the login page
- [ ] the admin from 8.1 can sign in; a wrong password says so without telling you which
      half was wrong
- [ ] a deep link like `https://<frontend domain>/admin/stadiums` loads directly (not a 404)
- [ ] users, stadiums, bookings, games and payments all list real rows
- [ ] a pending stadium can be approved, and the audit log records it

**The deployment itself**

- [ ] `https://<frontend domain>/api/stadiums` answers JSON in a browser — the forward works
- [ ] the backend has **no** public domain (its Networking tab shows none)
- [ ] the browser console shows no Content-Security-Policy violations, on the Mini App, the
      stadium map and the admin panel
- [ ] after a redeploy of the backend, a stadium's photos are still there (the volume works)
- [ ] the audit log shows a real client IP for the admin sign-in, not an internal one
      (`forward-headers-strategy: native`, set by the `staging` profile)

## 10. One bot, one poller

**Only one process may poll a Telegram bot.** `getUpdates` hands each update to whoever asks
first, so two pollers on the same token means every second message goes missing — and
polling also **deletes that bot's webhook**.

So, while the Railway deployment is running:

- **stop your local backend**, or
- give the local one a **different test bot**: its own `BOT_TOKEN` in `.env`, with
  `TELEGRAM_BOT_MODE=polling`.

Two test bots is the comfortable setup: one for Railway, one for your machine. And never
point either at the production bot's token — polling would delete the webhook production
depends on.

## 11. Logs, redeploy, roll back, cost

**Logs.** Service → **Deployments** → a deployment → **Build Logs** (the Maven or npm run)
and **Deploy Logs** (the running process). The project's **Observability** tab shows all
services at once. Logs are searchable; the backend logs no tokens, card numbers, message
text or phone numbers — a phone that must appear is masked (`+998 90 *** ** 58`).

**Redeploy.**

- Pushing to `feature/railway-test` deploys that service automatically.
- By hand: service → **Deployments** → **⋮** on the latest → **Redeploy**.
- After changing a **`VITE_*`** variable you must **redeploy the frontend** — those values
  are compiled into the bundle. A restart is not enough.
- After changing a backend variable, a **restart** is enough.

**Roll back.** Service → **Deployments** → an older successful deployment → **⋮** →
**Rollback**. It redeploys that exact build.

Two things do not roll back with it:

- **the database.** Liquibase changesets are applied forward; rolling the app back does not
  undo them. Rolling back across a changeset means the old jar meets a newer schema — and
  `ddl-auto: validate` may then refuse to start. For a test deployment the honest answer is
  to roll forward instead.
- **the volume.** Photos uploaded after the old build stay where they are.

**Keeping costs low.** This is a test deployment; it does not have to run all the time.

- Both services are tiny: 1 replica each, the smallest volume.
- Railway bills for what the services use while they are up. **Remove the frontend's domain
  and stop both services** when you are not testing — or, in the project's settings, pause
  the environment if your plan offers it.
- Each push to the deployed branch builds and deploys. Push to another branch while you work
  and merge into `feature/railway-test` when you actually want a new test deployment.
- Watch the **Usage** page in your account for the first few days to see what it actually
  costs.
- The database is the one thing worth keeping up, and it is also the cheapest to keep.

## 12. Troubleshooting

**The backend stops at startup with a list of settings.** That is the required-settings
check: it names every missing variable at once with what each one is. Add them (section 6)
and redeploy.

**`Could not resolve placeholder 'BOT_TOKEN'` or similar.** The same cause, for a variable
the list does not cover. Set it.

**The healthcheck times out.** Check the deploy log first:

- `Liquibase ... Connection refused` / `password authentication failed` → the `DB_*`
  variables or their `${{Postgres....}}` references are wrong. Check the Postgres service's
  Variables tab for the real names.
- The app logs `Tomcat started on port 8080` but the healthcheck still fails → `PORT` and
  the port Tomcat reports disagree. Set `PORT=8080` on the backend.
- Nothing after `Started MinipolyaBackendApplication` → `SERVER_ADDRESS` is missing, so the
  app is bound IPv4-only on an IPv6 private network. Set `SERVER_ADDRESS=::`.
- Still timing out on a cold start → raise Healthcheck Timeout.

**`TELEGRAM_BOT_MODE=polling ... needs the dev or staging profile`.**
`SPRING_PROFILES_ACTIVE` is not `staging`.

**`The dev profile ... must not be active together with prod, staging or atmos`.** Something
put `dev` into `SPRING_PROFILES_ACTIVE`. Remove it — the dev sign-in must not exist on a
server.

**`app.payments.provider=fake must never run under the prod or atmos profile`.**
`SPRING_PROFILES_ACTIVE` says `prod` (or `atmos`). For this deployment it must say `staging`.

**`MINIAPP_URL must be an https URL`.** Set it to the frontend's `https://` domain, with no
trailing slash.

**The frontend answers 403 to everything.** The `Host` header is not in
`preview.allowedHosts` (`vite.config.ts`). `*.up.railway.app` and
`healthcheck.railway.app` are allowed; a custom domain would have to be added there.

**The frontend's healthcheck fails although the page loads in a browser.** Same cause, for
Railway's own request: `healthcheck.railway.app` must be in `preview.allowedHosts`, or the
healthcheck gets a 403. It is in the list — check you have the current
`feature/railway-test` commit. As a stopgap you can clear the frontend's Healthcheck Path,
and Railway will only wait for the port to open.

**`/api/**` answers 502.** The frontend cannot reach the backend:

- `BACKEND_INTERNAL_URL` must be `http://backend.railway.internal:8080` — `http`, the exact
  service name, and the backend's `PORT`.
- the backend must be deployed and healthy;
- the backend must have `SERVER_ADDRESS=::`.

**Photos 404 after a redeploy.** `UPLOADS_DIR` is not under the volume's mount path, or no
volume is attached. It must be `/data/uploads` with the volume mounted at `/data`. Photos
uploaded before the volume existed are gone; delete those rows.

**The bot answers nothing, or every second message.** Two pollers on one bot token. See
section 10.

**The map does not load.** `VITE_YANDEX_MAPS_API_KEY` was not set **at build time**
(redeploy, do not restart), or the deployment's Referer is not allowed on the key (8.4), or
the free daily limit is used up. The Mini App falls back to OpenStreetMap, so this is never
fatal.

**The admin panel's deep links 404.** Only possible if the start command is not `npm start`:
the `/admin/*` rewrite lives in `vite.config.ts` and runs in the preview server.

**Every admin shares one sign-in budget, or the audit log shows an internal IP.**
`SPRING_PROFILES_ACTIVE` is not `staging`, so the forwarded-headers setting is off.

## 13. Later, for production

This deployment is deliberately a test. Production needs, roughly in this order:

- **Real payments.** The ATMOS provider instead of `FakePaymentProvider`:
  `SPRING_PROFILES_ACTIVE=prod,atmos`, every `ATMOS_*` variable, each owner's signed
  contract and recipient id entered by an admin, and the open ATMOS questions in
  `CLAUDE.md` settled against the real API. `prod` refuses to start with the fake provider,
  which is the point.
- **Webhook mode instead of polling.** `TELEGRAM_BOT_MODE=webhook` with a 32+ character
  `TELEGRAM_WEBHOOK_SECRET`, and `setWebhook` pointed at
  `https://<domain>/webhooks/telegram` with that secret. Polling does not survive more than
  one instance and adds latency.
- **Custom domains**, and a **separate domain for the admin panel** — a different origin
  from the Mini App, so a Mini App bug cannot reach admin storage, and the panel can sit
  behind an allow-list or a VPN.
- **A real static server for the frontend.** `vite preview` is a development tool: it is
  fine for a test deployment, it is not a production web server. Serve `dist/` with Nginx,
  Caddy or a CDN, which also gives proper asset caching and compression.
- **Docker.** A `Dockerfile` for each service pins the JDK and Node versions, the base image
  and the build steps, so the image is the same everywhere and does not depend on a
  builder's defaults.
- **Backups.** Automated daily `pg_dump` (or PITR) **and a restore you have actually tried**,
  plus the uploads volume backed up with it — a photo row is useless without its file.
- **Infrastructure as code.** Railway has an Infrastructure-as-Code format
  (`.railway/railway.ts`, applied with `railway config apply`) that would keep these
  settings in the repository instead of in the dashboard. Worth adopting once it is mature
  and once the service layout has stopped changing; for now the dashboard values in sections
  4–6 are the source of truth. (The older `railway.json` "config as code" is deprecated and
  new services cannot opt into it.)
- The rest of the production checklist — reverse proxy, HTTPS, rate limiting, log review,
  one instance and what a second would need — is in the backend's `CLAUDE.md`, "Deployment
  checklist".
