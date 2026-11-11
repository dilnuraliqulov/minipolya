# API changes for the frontend — deposit / remainder, owner marks, challenges, 5 000 soʻm rounding (2026-11-06)

Backend half of `docs/superpowers/specs/2026-11-06-deposit-remainder-design.md` (E1–E10).
Money is tiyin, as always. Errors use the usual envelope
`{ "success": false, "data": null, "message": "<text>" }`. Absent fields are omitted from
the JSON (never `null`).

## 1. Rounding to 5 000 soʻm (E1, E2)

No field changes. `platform_settings.payment_rounding_step` is now **500 000 tiyin**
(5 000 soʻm) by default (changeset 049; an admin-set value is kept), so every player
amount is a multiple of 500 000: owner 150 000 soʻm → player **155 000**; minimum 50 000 →
deposit **55 000**; remainder = price − deposit = **100 000**. Team `amountToPayNow` /
`remainderAmount` likewise. `GET /api/admin/settings` shows `paymentRoundingStep: 500000`.
The frontend must not compute any of these — use the API values.

## 2. Availability slots — `GET /api/stadiums/{id}/availability` (E3)

Each item of `slots[]` (`TimeSlot`):

| Field | Type | When present |
|---|---|---|
| `depositAmount` | Long, tiyin | **new** — a `free` slot of a stadium with a minimum whose deposit is below `pricePerHour`. Exactly what `POST /api/bookings` with `paymentOption: "MINIMUM"` charges |
| `remainderAmount` | Long, tiyin | **new** — with `depositAmount`: `pricePerHour − depositAmount` (same in the app or in cash) |

Both absent ⇒ full payment only (no choice cards, E12). Never present on `booked`,
`closed` or `past` slots.

## 3. Booking responses (E4, E8)

`BookingResponse` — player (`GET /api/bookings`, `GET /api/bookings/{id}`, create / pay /
pay-remainder / cancel answers), owner (schedule, unpaid list, cash / not-paid / undo
answers) and admin (`AdminBookingResponse`, same fields unwrapped):

| Field | Type | Player | Owner | Admin | Meaning |
|---|---|---|---|---|---|
| `paidInCash` | Long, tiyin | ✓ | ✓ | ✓ | **new**, always present (0 when none): cash the owner took that still counts. A cash row voided by an undo does not count |
| `remainderMark` | `"cash_received"` \| `"not_paid"` | ✓ | ✓ | ✓ | **new**, absent when there is no mark |
| `remainderMarkedAt` | ISO-8601 UTC | — | ✓ | ✓ | **new**, when the mark was set; absent with no mark |
| `remainderMarkUndoableUntil` | ISO-8601 UTC | — | ✓ | — | **new**, the end of the Tashkent day the mark was set: the owner's undo button is allowed before it; absent with no mark |

Status chip (E10), from these fields: `paymentStatus = paid` and `paidInCash = 0` →
"Toʻliq toʻlangan"; `remainderMark = cash_received` → "Naqd olindi"; `remainderMark =
not_paid` → "Toʻlanmadi"; else `remainingAmount > 0` → "Qoldiq: <remainingAmount>".
Player: `remainingAmount > 0` → "Qolgan summa" + "Ilovada toʻlash" + "yoki maydonda naqd
toʻlang"; `paidInCash > 0` → "Maydonda naqd toʻlangan".

`AdminBookingResponse.cashReceived` / `cashReceivedAt` are unchanged but no longer count a
voided cash row.

## 4. Owner endpoints (E5–E7)

All need the owner role. Each answers the **owner** `BookingResponse` (§3).

### `POST /api/owner/bookings/{id}/cash` — "Naqd olindi" (changed)

Records `remainingAmount` in cash **and now sets `remainderMark: "cash_received"`**
(replacing `not_paid`). Message "Cash recorded".

| Case | Before | **Now** |
|---|---|---|
| booking cancelled / expired / pending, or a game booking | 400 "This booking has no part payment to complete" | **409** same message |
| nothing left (paid in full) | 400 "Already paid" | **409** "Already paid" |
| card payment still being processed | 409 "A payment for this booking is being processed" | unchanged |
| not the stadium's owner | 403 "You do not own this stadium" | unchanged |
| unknown booking | 404 "Booking not found" | unchanged |

### `POST /api/owner/bookings/{id}/not-paid` — "Toʻlanmadi" (new)

No body. Sets `remainderMark: "not_paid"`; nothing is charged, no penalty, the remainder
stays. Message **"Marked as not paid"**. Sending it again changes nothing (200). Same
403 / 404 / 409 cases and messages as `/cash` (except the "being processed" one). The
player paying the rest in the app (`POST /api/bookings/{id}/pay-remainder`) clears the
mark; `/cash` later replaces it.

### `DELETE /api/owner/bookings/{id}/remainder-mark` — undo (new)

Message **"Mark undone"**. Allowed until `remainderMarkUndoableUntil`.

- `cash_received`: the cash row is voided (payment status `cancelled`, not a refund);
  `paidAmount`, `remainingAmount`, `paymentStatus`, `paidInCash` go back to what they were
  before the cash (e.g. `partially_paid`, or `unpaid` for a walk-in); the mark is cleared.
- `not_paid`: only the mark is cleared.

| Case | Status | Message |
|---|---|---|
| after the mark's Tashkent day | **409** | "Only an admin can change this mark" |
| no mark | **409** | "This booking has no remainder mark" |
| not the stadium's owner | 403 | "You do not own this stadium" |
| unknown booking | 404 | "Booking not found" |

Works for app bookings and walk-ins (a manual booking added unpaid). A walk-in paid in
cash when it was added has no mark.

## 5. Admin endpoint (E6)

### `DELETE /api/admin/bookings/{id}/remainder-mark` (new)

Admin only. Undo any mark at any time, same effect as the owner's undo. Answers the
`AdminBookingResponse` (§3), message **"Mark undone"**. No mark → **409** "This booking
has no remainder mark"; unknown booking → 404 "Booking not found"; non-admin → 403.

## 6. Challenge board — `GET /api/games/challenges` (E9)

Each `GameChallengeResponse` item:

| Field | Type | Meaning |
|---|---|---|
| `stadiumPhotoUrl` | String | **new** — the stadium's cover photo (`/api/uploads/...`), absent when it has none |
| `stadiumDistrict` | String | **new** — one of the 12 Tashkent districts, absent for older stadiums |

Unchanged: filtering (3-hour rule, joining games and the caller's own teams excluded),
`amountToPayNow` (the amount to show), `remainderAmount`, `totalPrice`.

## 7. Audit log codes (admin panel filter)

New in `GET /api/admin/audit-log/actions`: `REMAINDER_NOT_PAID` (OWNER),
`REMAINDER_MARK_UNDONE` (OWNER, or ADMIN for the admin's undo). `CASH_RECEIVED` now also
carries a `remainderMark` change; `BOOKING_REMAINDER_PAID` shows a cleared `not_paid`.

## 8. Payments

`status` may now be **`cancelled`** on a cash payment (`method: "cash"`) voided by an undo
— in the admin payments list / detail, and in `GET /api/payments/my` of the owner who
recorded it (cash rows are recorded under the owner). `GET /api/admin/payments?status=cancelled`
filters them.

## 9. Frontend `messages.ts` strings to add / adjust

- "Marked as not paid", "Mark undone" (success messages)
- "Only an admin can change this mark" (409)
- "This booking has no remainder mark" (409)
- "Already paid" and "This booking has no part payment to complete" now arrive as **409**
  from `/cash` and `/not-paid` (they were 400 from `/cash`)
