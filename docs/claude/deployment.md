# Deployment

> Part of the MiniPolya CLAUDE.md doc set. **Read this before deploying, or changing the reverse proxy, HTTPS, secrets, backups or the Docker Compose test server.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## The test deployment (`deploy/`)

One Ubuntu server on AWS EC2, Docker Compose, three containers. `deploy/` holds all
of it, and `deploy/DEPLOYMENT-AWS.md` is the step-by-step guide — the AWS console,
the server, the secrets, the first admin, @BotFather, Yandex, backups, costs. It is
the **only** copy; the frontend repository points at it rather than duplicating it.

| File | What it is |
|---|---|
| `deploy/docker-compose.yml` | the three services, the volumes, the memory limits |
| `deploy/Caddyfile` | routing, automatic HTTPS, caching, the security headers and CSP |
| `deploy/.env.example` | every variable, with the `openssl` command for each secret |
| `deploy/backup.sh` | the nightly `pg_dump`, 14 days, run from cron |
| `deploy/DEPLOYMENT-AWS.md` | the walkthrough |
| `Dockerfile` (repo root) | Maven on JDK 21 → JRE 21, non-root, container memory flags |

What is worth knowing without reading all of it:

- **`SPRING_PROFILES_ACTIVE`**, from the server's `.env`, `staging` when it is not set —
  see "Profiles". Fake payments, bot polling, no dev sign-in, real secrets required.
  `staging,atmos` points the same server at the real ATMOS sandbox instead: fill in the
  `ATMOS_*` values (the first four are required to start) and the fake provider is not
  created. ATMOS only answers calls from an IP it has allowed, which is this server's —
  never a laptop. Never add `dev`.
- **Only Caddy publishes ports**, 80 and 443. Neither the backend nor PostgreSQL has a
  `ports:` entry, so from outside the server they do not exist. Do not add one: Docker
  writes its own iptables rules and `ufw` would not stop it. That isolation is also
  what makes `X-Forwarded-For` trustworthy — nothing else can set it.
- **One origin** for the Mini App, the admin panel and the API, so `CORS_ALLOWED_ORIGINS`
  stays empty and the frontend is built with `VITE_API_URL` empty.
- **The frontend image is built from the other repository**, as
  `../../minipolyaFrontend` — the two checkouts sit side by side in `~/minipolya/`.
- **The Caddyfile is mounted, not baked in**, so a header or route change is
  `docker compose exec caddy caddy reload --config /etc/caddy/Caddyfile`. Its CSP must
  stay on one line: an HTTP header cannot contain a newline, and Caddy silently drops a
  header it cannot send.
- **`/actuator/health`** is the container health check and the only actuator endpoint
  exposed (`show-details: never`).

## Deployment checklist

Go through this before the first **production** deploy and again whenever the
infrastructure changes. The app is built to run as **one instance behind a reverse
proxy**. For the AWS EC2 **test** deployment follow `deploy/DEPLOYMENT-AWS.md`
instead — it is a complete walkthrough and supersedes this checklist for that server.

## Reverse proxy and client IPs

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

## HTTPS only

- Nginx terminates TLS (TLS 1.2+); the app itself speaks plain HTTP on localhost.
- Port 80 only redirects: `return 301 https://$host$request_uri;`.
- Send HSTS: `add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;`.
- Telegram requires HTTPS anyway, for the Mini App URL and for the bot webhook.
- JWTs and admin passwords travel in every request, so there is no "internal HTTP"
  exception, admin panel included.

## Secrets and configuration

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
  without `ATMOS_BASE_URL`, `ATMOS_CONSUMER_KEY`, `ATMOS_CONSUMER_SECRET` and
  `ATMOS_STORE_ID` (`ATMOS_PLATFORM_RECIPIENT_ID` is needed before a team-game fee can be
  paid out, not to start). Set `CARD_TOKEN_KEY` (`openssl rand -base64 32`)
  and keep it: losing it makes every saved card unusable. Each stadium owner's signed
  contract and ATMOS recipient id are entered by an admin
  (`PUT /api/admin/users/{id}/legal-details`) — a stadium cannot be approved before
  that. Settle the "Open ATMOS
  questions" (see "Payments") against the real API first.
- The Payme/Click webhooks are still mocks that trust the body without checking a
  signature (see "Deferred until the payment model is final"). Don't expose
  `/webhooks/payme` and `/webhooks/click` until they verify it — block them in
  Nginx otherwise.

## Build and run

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

## Database

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

## Application settings worth checking

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

## After the first deploy

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

