# Admin panel API

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching anything under `/api/admin/**`, including list filters and the revenue figures.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

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

## Endpoints

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
| `PUT /api/admin/stadiums/{id}/booking-terms` | `{ minBookingAmount: Long\|null, cancellationHours: Integer }` — see "Cancellation Policy"; any status; answers the same detail as `GET /api/admin/stadiums/{id}` (which carries `landmark`, `amenities`, `photos`, `latitude` / `longitude`, `cancellationPolicy`, `cancellationHours`, `minBookingAmount`, `hours`) |
| `PUT /api/admin/stadiums/{id}/hours` | `{ hours: { days: [{ dayOfWeek, closed, openTime, closeTime }] } }` — the whole week, agreed with the owner; any status, the row locked; the same rules as the owner's (see "Opening hours and the working day"); audited `STADIUM_HOURS_CHANGED`; answers the admin detail, "Opening hours updated" |
| `GET` / `POST /api/admin/stadiums/{id}/notes` | admins' internal notes, newest first; `{ text }` 1–1000 chars. Never shown to owners or players |
| `GET /api/admin/users` | filters `search`, `role` (`player` = neither owner nor admin, `owner`, `admin`), `status` (`active`, `blocked`) |
| `GET` / `PUT /api/admin/users/{id}/legal-details` | see "Owner legal details"; GET answers `data: null` before anything is entered |
| `GET /api/admin/users/{id}/teams` | the teams a user leads or belongs to: `id`, `name`, `leaderName`, `memberCount`, `role` |
| `GET /api/admin/stats` | overview figures (see "Revenue"), plus `failedRefunds` / `oldestFailedRefundAt`, `newUsersLast7Days`, `rejectedStadiums`, and the previous-period values `todayBookingsLastWeek`, `previousMonthRevenueToDate`, `newUsersPrevious7Days`, `approvedStadiumsWeekAgo` (the panel works out the %) |
| `GET /api/admin/stats/revenue` | `appRevenue` per Tashkent day, last 30 days, every day present; `bookingCount` = the booking series below |
| `GET /api/admin/stats/bookings-daily` | bookings starting each of the last 30 Tashkent days (confirmed or completed, like `todayBookings`), every day present |

## Revenue

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

## Failed refunds

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

## Owner legal details

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

