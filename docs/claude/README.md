# `docs/claude/` — the detail docs behind CLAUDE.md

`CLAUDE.md` is loaded into every session, so it keeps only the hard rules, the structure, the
conventions, the commands and an index. Everything else moved here, **verbatim**, on
2026-11-18 (CLAUDE.md went from 159 351 to ~20 400 characters).

These files are **not** `@imports` — nothing here is loaded automatically. Open the one that
covers what you are about to change; `CLAUDE.md` says which that is.

Quoted cross-references inside these files (`see "Cancellation Policy"`) still name a
*section*, which may now live in another file. The table below says which.

## Where each old CLAUDE.md section went

| Old section | Now in |
|---|---|
| Tech Stack | `CLAUDE.md` (kept) |
| Project Structure | `CLAUDE.md` (kept, condensed to fewer lines — same names and notes) |
| Database · Changeset Files · Adding New Migrations | `database.md` |
| Key Business Rules (the bare heading) | dropped — its subsections are listed below |
| Users & Roles | `users-and-auth.md` |
| Stadiums | `stadiums.md` |
| Opening hours and the working day | `opening-hours.md` |
| Bookings (incl. Paying for a booking, owner marks) | `bookings.md` |
| Cancellation Policy | `bookings.md` |
| Recurring Bookings (booking_series) | `bookings.md` |
| Teams | `teams.md` |
| Games (3 separate flows) — casual, open, Common game rules | `games.md` |
| Games — team_vs_team | `team-games.md` |
| Platform Settings | `pricing.md` |
| Payments → The player price · API fields | `pricing.md` |
| Payments → declines, the provider, fake-provider recipes, payment rows, team money → ATMOS operations, reconciliation, saved cards, open ATMOS questions | `payments.md` |
| Availability Calculation | `availability.md` |
| Audit log (all subsections) | `audit-log.md` |
| Admin panel API · Revenue · Failed refunds · Owner legal details | `admin-api.md` |
| Owner API · Stadium photos | `owner-api.md` |
| Auth (Telegram, dev sign-in, login widget, admin accounts, JWT, Endpoint Security) | `users-and-auth.md` |
| Telegram bot (all subsections) | `telegram-bot.md` |
| API Response Format | `CLAUDE.md` (kept) |
| Coding Conventions | `CLAUDE.md` (kept) |
| Environment Variables · managed-database variables · required settings | `environment.md` |
| Profiles · The `staging` profile | `environment.md` |
| The test deployment (`deploy/`) | `deployment.md` |
| Deployment checklist (all subsections) | `deployment.md` |
| Commands | `CLAUDE.md` (kept); the `@DatabaseTest` note → `testing.md` |
| Dependencies (pom.xml) · MapStruct + Lombok Maven Config | `dependencies.md` |
| Build Order | `build-order.md` (history) |
| Important Notes | `CLAUDE.md` → "Hard rules → Other invariants" (verbatim bullets) |
| Deferred until the payment model is final | `deferred.md` |

## Files

| File | Read it |
|---|---|
| `database.md` | schema, Liquibase changesets, entities, id / money / timestamp types |
| `users-and-auth.md` | users, roles, Telegram and dev sign-in, admin password accounts, JWT, endpoint security |
| `stadiums.md` | stadium creation, review and approval, districts, amenities, the public list |
| `opening-hours.md` | the weekly schedule and the working day |
| `bookings.md` | bookings, holds, paying, remainders, owner marks, cancellation, recurring series |
| `availability.md` | the availability endpoint, slot statuses, the free-slot count |
| `pricing.md` | the player price, card fee, rounding, minimum booking amount, platform settings |
| `payments.md` | the provider, declines, refunds, holds, payment rows, saved cards, reconciliation |
| `teams.md` | teams, members, invite links |
| `games.md` | casual and open games, rules shared by all three flows |
| `team-games.md` | `team_vs_team`: holds, joins, deadlines, cancellation bands, remainders, the board |
| `audit-log.md` | what is recorded, how to write an entry, what is never logged, the admin endpoints |
| `admin-api.md` | `/api/admin/**`, list conventions, revenue, failed refunds, owner legal details |
| `owner-api.md` | `/api/owner/**`, the dashboard, stadium photos |
| `telegram-bot.md` | webhook / polling, the conversation, texts, the banner |
| `environment.md` | `.env`, every variable, the profiles |
| `deployment.md` | the Compose test server and the production checklist |
| `testing.md` | how to run tests and the separate test database |
| `dependencies.md` | dependencies and the MapStruct / Lombok processor order |
| `deferred.md` | known-unfinished money questions |
| `build-order.md` | the original vertical-slice build order (history) |
