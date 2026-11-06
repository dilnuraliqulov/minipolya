# API changes for the frontend — team vs team: minimum / full payment per team (2026-11-06)

Backend half of `docs/superpowers/specs/2026-11-06-team-game-min-full-design.md` (T1–T11).
Money is tiyin, as always. Errors use the usual envelope
`{ "success": false, "data": null, "message": "<text>" }`. Absent fields are omitted from the
JSON (never `null`). The frontend must not compute any amount — use the API values.

## The example (default settings: fee 25 000 soʻm, card fee 1.5 %, step 5 000 soʻm)

Pitch price P = 200 000 soʻm, stadium minimum M = 50 000 soʻm. Per team:

| Option | Player pays now | Left to pay (app or cash) | Platform nets | Owner part (net) |
|---|---|---|---|---|
| `minimum` | **55 000** | **75 000** (= 130 000 − 55 000) | 25 000 | 25 000 |
| `full` | **130 000** | 0 | 25 000 | 100 000 |

In tiyin: 5 500 000 / 7 500 000 / 13 000 000.

## 1. Every stadium has a minimum (T1–T3)

- New platform setting **`defaultMinBookingAmount`** (tiyin, default 5 000 000 = 50 000 soʻm),
  used by every stadium without a minimum of its own.
- **Booking availability** (`GET /api/stadiums/{id}/availability`): every free slot now carries
  `depositAmount` / `remainderAmount`, unless the deposit would be the whole slot price (then
  both are absent, full payment only). Before, a stadium without a minimum never had them.
- **`POST /api/bookings` with `paymentOption: "MINIMUM"`** now works on every stadium (the
  default minimum is used). The 400 "This stadium requires full payment; it has no minimum
  booking amount" is **gone**.
- **Public stadium detail** (`GET /api/stadiums/{id}`): `minBookingAmount` (player price) is now
  always present — the stadium's own, or the platform default's.
- **Owner / admin stadium detail**: `minBookingAmount` stays as set (absent = the platform
  default is used); `playerMinBookingAmount` is now always present (the effective minimum's
  player price).

### Admin

| Endpoint | Change |
|---|---|
| `GET /api/admin/settings` | **new** field `defaultMinBookingAmount` (Long, tiyin) |
| `PUT /api/admin/settings` | **new** optional field `defaultMinBookingAmount`. 400 **"defaultMinBookingAmount must be more than the team game platform fee (25 000 so'm)"** (the fee in force, or the one sent with it); 400 **"teamGamePlatformFee must be less than the default minimum booking amount (50 000 so'm)"** when a new fee would reach the default; 400 "defaultMinBookingAmount must not be negative". Audited `SETTINGS_CHANGED` (`defaultMinBookingAmount` old → new) |
| `PUT /api/admin/stadiums/{id}/booking-terms` | `minBookingAmount: null` (or absent) now means **"use the platform default"** (there is no full-payment-only stadium any more). New 400 **"minBookingAmount must be more than the team game platform fee (25 000 so'm)"**. The other rules are unchanged (≥ 50 000 tiyin, ≤ `pricePerHour`) |

A stadium minimum stored earlier that is not above the team fee is ignored at runtime (the
default is used); nothing is rewritten.

## 2. Quotes (T7) — new endpoints

Both answer `TeamGameQuoteResponse`, player amounts only:

```json
{
  "options": [
    { "option": "minimum", "amount": 5500000,  "remainderAmount": 7500000 },
    { "option": "full",    "amount": 13000000, "remainderAmount": 0 }
  ],
  "cancellationDeadline": "2026-11-08T15:00:00Z"
}
```

`minimum` is listed only when the stadium offers it for this game (its effective minimum is
below the team's full net `P/2 + fee`); `full` is always there, last. When only `full` is
listed, show no choice.

| Endpoint | Auth | Errors |
|---|---|---|
| `GET /api/stadiums/{id}/team-game-quote?startsAt=<ISO-8601>` — creating ("Raqib qidirish"), side A | public | 400 an hour that cannot be booked: "startsAt must be in the future", off the hour / outside opening hours / closed (the booking-window messages), "That time slot is already booked"; 404 "Stadium not found" (unknown, not approved or inactive) |
| `GET /api/games/{id}/join-quote` — accepting, the free side | signed in | 404 "Game not found"; 400 "Only a team game has a join quote", "Game is not open for joining", `O'yin boshlanishiga 3 soatdan kam qoldi, qo'shilib bo'lmaydi` |

## 3. Creating and joining (T6)

| Endpoint | New request field |
|---|---|
| `POST /api/games` (`type: "team_vs_team"`) | `paymentOption`: `"MINIMUM"` \| `"FULL"` (uppercase, like bookings). Absent → `MINIMUM` |
| `POST /api/games/{id}/join` (team game) | `paymentOption`: `"MINIMUM"` \| `"FULL"`. Absent → `MINIMUM` |

The creating team's amount is **held**, the joining team's **charged**, then the hold
captured — the same flow, statuses and messages as before (202 `PAYMENT_PENDING`, declines,
502, …). A `MINIMUM` request on a game where the minimum is not offered is taken as `FULL`
(no error). Invalid values (anything but the two) → 400 (JSON parse).

## 4. Challenge board (T8) — `GET /api/games/challenges`

| Field | Change |
|---|---|
| `amountToPayNow` | now the **minimum** option's amount (55 000 soʻm in the example); the full amount when no minimum is offered |
| `remainderAmount` | what is left after `amountToPayNow` (75 000) |
| `fullAmount` | **new** — the full option's amount (130 000) |
| `totalPrice` | what both teams pay altogether: a team already in counts what it fixed on joining; a free side counts in full |

## 5. Team money in game responses (T9) — `GameDetailResponse.teams[]`

(`GET /api/games/{id}`, create / join / pay-remainder / cash / not-paid / undo answers; the
admin's `GET /api/admin/games/{id}` `teams[]` has them too.)

| Field | Type | Player | Owner | Admin | Meaning |
|---|---|---|---|---|---|
| `paymentOption` | `"minimum"` \| `"full"` | ✓ | ✓ | ✓ | **new**; absent on games created before this change |
| `paidAmount` | Long | ✓ | ✓ | ✓ | **new** — player money the team has put in: held on its card or arrived, card or cash (a voided cash row does not count). "Toʻlangan: X" — also while held |
| `remainingAmount` | Long | ✓ | ✓ | ✓ | **new** — what the team still owes now: `remainderAmount` while unpaid, else 0. Show "Qolgan summa" / "Ilovada toʻlash" / "yoki maydonda naqd" only when > 0 |
| `remainderMark` | `"cash_received"` \| `"not_paid"` | ✓ | ✓ | ✓ | **new**, absent with no mark ("Naqd olindi" / "Toʻlanmadi") |
| `remainderMarkedAt` | ISO-8601 UTC | ✓ | ✓ | ✓ | **new**, absent with no mark |
| `remainderMarkUndoableUntil` | ISO-8601 UTC | — | ✓ | — | **new**, only for the stadium's owner: the end of the Tashkent day the mark was set (the owner's undo is allowed before it) |
| `ownerPart` | Long | — | — | ✓ | **new**, admin only: the owner's net part of the team's joining payment (M − fee for minimum, its half of P for full) |
| `price` | Long | ✓ | ✓ | ✓ | unchanged meaning: what the team paid (or holds) on joining |
| `remainderAmount` | Long | ✓ | ✓ | ✓ | unchanged meaning: the team's rest (full player amount − `price`), whether paid yet or not |

Existing fields (`remainderStatus`, `paidInApp`, `heldAmount`, `cancellationDeadline`,
`refundIfCancelledNow`; admin `platformFee`, `ownerAdvance`, `ownerRemainder`,
`ownerReceives`, `atmosFee`) stay. "Toʻliq toʻlangan" = `remainingAmount == 0` with no
`cash_received` mark.

The game's **booking** (owner schedule / unpaid list, `source: "game"`): when every team in
the game chose an option, the booking counts in player terms — `price` = what both teams pay
altogether, `paidAmount` what they paid, `remainingAmount` = the teams' unpaid rests exactly.

## 6. Owner marks on a team's remainder (T10)

| Endpoint | Body / query | Answer |
|---|---|---|
| `POST /api/games/{id}/cash` | `{ "side": "a" \| "b" }` | records the rest in cash **and** sets `cash_received` (replacing `not_paid`); `GameDetailResponse`, "Cash recorded" |
| `POST /api/games/{id}/not-paid` | `{ "side": "a" \| "b" }` | **new** — `not_paid`: no charge, no penalty; marking again changes nothing; "Marked as not paid" |
| `DELETE /api/games/{id}/remainder-mark?side=a\|b` | — | **new** — the owner's undo, until `remainderMarkUndoableUntil`; undoing cash voids that cash row (status `cancelled`), the rest is owed again; "Mark undone" |
| `DELETE /api/admin/games/{id}/remainder-mark?side=a\|b` | — | **new** — an admin, any time, same effect; answers the admin game detail, "Mark undone" |

Statuses and messages:

- **403** "You do not own this stadium" (not the stadium's owner).
- **409** "This game is cancelled"; **409** "This game is not confirmed yet" (a waiting game);
  **409 "Already paid"** — nothing left (paid in full, or settled). **Changed: the cash call
  answered 400 "Already paid" before**; the player's own `pay-remainder` still answers 400.
- **409** "A payment for this team is being processed" (cash while a card payment of the rest
  is unconfirmed).
- **409** "This team has no remainder mark" (undo with no mark); **409** "Only an admin can
  change this mark" (the owner's undo after the day).
- **404** "Game not found"; **404** "No team is on side a|b"; **400** "side must be 'a' or 'b'".
- Paying the rest in the app (`POST /api/games/{id}/pay-remainder`) **clears** `not_paid`.
- Audited on the game: `CASH_RECEIVED` (`teamX.remainderMark`), `REMAINDER_NOT_PAID`,
  `REMAINDER_MARK_UNDONE` (OWNER, or ADMIN), `GAME_REMAINDER_PAID` (with the cleared mark).

## 7. Unchanged (T11)

Cancellation / refund bands are the same, applied to what each team actually paid: a full
team gets its 130 000 back when the band says everything back, a minimum team its 55 000
(plus a rest it paid in the app). Cash is never refunded through the platform.
