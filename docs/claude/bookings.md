# Bookings

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching bookings, holds, payment of a booking, remainders, owner marks, cancellation or recurring series.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Bookings

- Source types:
  - `app` → player booked via Mini App
  - `manual` → owner added walk-in/phone booking (client_name, client_phone, is_walkin=true)
  - `game` → created automatically when a game is confirmed
- **A booking is exactly one hour** — one availability slot (`Booking.HOURS`), for player
  bookings, manual owner bookings, team and friends games and recurring bookings alike;
  `endsAt = startsAt + 1h`. The length fields that requests may still send
  (`durationHours` on `POST /api/bookings`; `duration` on manual bookings, series and
  games) are optional: absent or 1 is accepted, anything else is **400 "A booking is
  exactly one hour"** (`BookingWindowValidator.requireOneHour`). Rows stored before this
  rule may be longer and keep their length; an older series row that says more than one
  hour generates one-hour bookings. There is no multi-hour code path — don't add one.
  - `startsAt` must be on the hour in `Asia/Tashkent` (minutes/seconds/nanos = 0) so bookings line up with the hourly availability slots.
  - The hour (`startsAt` → `startsAt + 1h`) must belong to one of the stadium's **working days** (see "Opening hours and the working day"); otherwise 400 "The stadium is not open at that hour". An hour after midnight belongs to the day that opened the evening before.
  - The hour must not fall in any `stadium_closure` **of its working day**.
  - Price = the slot's price (pricing rule by local weekday/time, falling back to `stadium.price_per_hour`, `BookingPriceCalculator.hourPrice`), with the best active promotion for that hour applied — identical to what the availability endpoint shows for the slot.
  - Violations throw `BadRequestException` → HTTP 400. An overlap rejected by the DB EXCLUDE constraint reports 409 "Slot is already booked".
- Max booking: 4 weeks ahead
- **The player's list** (`GET /api/bookings`, paged): every booking, latest start first;
  R29 `when=upcoming` — `startsAt` now or later, soonest first; `when=past` — `startsAt`
  before now, newest first; any other value 400 "Invalid when". Each item carries
  `paymentState` and `declineReason` (see "Why a card payment was refused").
- Hold: unpaid bookings set `expires_at = now + 10 minutes`, status = `pending`
- Expiry: `BookingExpiryJob` runs every minute, marks expired pending bookings as `expired`
  — **except one whose card payment is still on its way** (`processing` or
  `capture_pending`): the slot stays held until the payment is resolved, because ATMOS
  answers a confirmation it has in fact taken the money for with a connection error. The
  query is `BookingRepository.findExpirablePending`; see "A confirmation ATMOS could not
  answer" in [payments.md](payments.md). Nothing waits for ever — the charge is failed after
  `PaymentReconcileService.ABANDONED_AFTER` and the next run expires the booking.
- Status flow:
  - `pending` → `confirmed` (paid) → `completed`
  - `pending` → `expired` (unpaid timeout)
  - `confirmed` → `cancelled_by_user`
  - `confirmed` → `cancelled_by_owner` (always 100% refund)

### Paying for a booking

`POST /api/bookings` takes a `cardId` (a saved card of the booking player) and a
`paymentOption`, and charges that card straight away:

| `paymentOption` | Charged | Allowed when |
|---|---|---|
| `FULL` | the player price **D**: the slot's player price | always |
| `MINIMUM` | the deposit **d** = `min(playerAmount(M), D)` | always (every stadium has a minimum, T1) |

All in player prices (see "Payments → The player price"). The rest, **r = D − d**, is
the same amount whether the player pays it in the app or the owner takes it in cash.

There are no other amounts. **M is the stadium's effective minimum** (T1,
`CardPricing.effectiveMinimum`): its own `min_booking_amount`, or
`platform_settings.default_min_booking_amount` (50 000 so'm) when it has none — or has one
not above the team-game fee, which is ignored at runtime, never rewritten. A stadium value,
when set, must be more than the team-game fee, at least 50 000 tiyin (500 so'm, the
smallest payment ATMOS takes) and at most the hour's own price. **Only an admin sets it**,
agreed with the owner by phone: `PUT /api/admin/stadiums/{id}/booking-terms` (see
"Cancellation Policy"; null = the platform default); the owner sees it read-only (R41's
`clearMinBookingAmount` is gone). There is no full-payment-only stadium any more: when the
deposit reaches the price, MINIMUM simply charges the whole of it.

- `bookings.payment_status` is `unpaid` / `partially_paid` / `paid`.
  `bookings.pricing_model` (changeset 040) says how the money is counted:
  - `player_price` — every booking from now on: `card_price` is D (the owner's own
    `price` on a manual booking) and `paid_amount` counts what the player paid, card
    or cash, so `remainingAmount = D − paid_amount`;
  - `net` — bookings made before the change keep their stored amounts and their old
    accounting (owner's price, net paid), and so do **game bookings**, whose teams'
    debts live on `game_teams`.
  Responses: see "Payments → API fields".
- A successful charge confirms the booking. A **declined** card leaves it
  `pending` with a `failed` payment row, so the usual hold timeout expires it;
  the create call still answers 200 with `paymentStatus: unpaid`, and the
  message says the payment failed.
- **Paying it again (R28):** `POST /api/bookings/{id}/pay` `{ cardId }` — the booking's
  own player, while it is `pending` and its hold has not run out, with no payment of it
  `processing` or arrived. The **same amount** as the first attempt (the deposit or the
  whole price), on the same path (`PaymentService.chargeForBooking`): success confirms it;
  a decline is still **200**, `pending`, the **same `expiresAt`** (the hold is never
  extended) and the new `declineReason`. The booking row is locked for the call, so two
  taps at once charge once. Errors: **409** `BOOKING_NOT_PENDING` "This booking is not
  awaiting payment", **409** `BOOKING_EXPIRED` "The booking's hold has expired; book the
  slot again", **409** `PAYMENT_IN_PROGRESS` "A payment for this booking is being
  processed"; 403 "This booking does not belong to you"; 404 "Booking not found" / "Card
  not found". Audited `BOOKING_PAYMENT_RETRIED` (PLAYER). Same messages as creation.
**Paying off the rest.** While a booking is `partially_paid` the remainder can be
settled two ways, and whichever lands first wins:

- `POST /api/bookings/{id}/pay-remainder` with a `cardId` — the player charges
  the whole remainder to a saved card, at any time, **before or after the game**.
  Paying after the game leaves the booking `completed`; it is never dragged back
  to `confirmed`.
- `POST /api/owner/bookings/{id}/cash` (R37, no body; "Naqd olindi") — the owner marks the
  same money as received in cash (it takes `remainingAmount`, not the full price), through
  `PaymentService.recordCashPayment`, and the booking gets `remainder_mark = cash_received`
  (E5); answers the owner's `BookingResponse`, "Cash recorded". See "Owner marks" below
  for who may and the 403 / 409 answers.

Both lock the booking row (`BookingRepository.findForUpdate`), so they settle one at a
time. Once `payment_status` is `paid`, the player's pay-remainder answers **400 "Already
paid"** and the owner's cash **409 "Already paid"**. The remainder is charged as a
`remainder` payment row, and a declined card leaves the booking `partially_paid` with a
`failed` row.

**Owner marks on the remainder (E5–E7, changeset 050).** `bookings.remainder_mark` is
`cash_received` ("Naqd olindi"), `not_paid` ("Toʻlanmadi") or null, with
`remainder_marked_at` / `remainder_marked_by`:

- `POST /api/owner/bookings/{id}/cash` sets `cash_received` (and records the cash, above).
- `POST /api/owner/bookings/{id}/not-paid` sets `not_paid`: no charge, no penalty, the
  remainder stands. "Marked as not paid"; marking again changes nothing. The player can
  still pay it in the app — that **clears** `not_paid` (`applySuccessfulBookingPayment`) —
  and the owner can still take cash later, which **replaces** it.
- `DELETE /api/owner/bookings/{id}/remainder-mark` undoes it, "Mark undone": allowed until
  the **end of the Tashkent day** of `remainder_marked_at`
  (`BookingService.markUndoableUntil`, shown as `remainderMarkUndoableUntil`); later
  **409 "Only an admin can change this mark"**. Undoing `cash_received` voids the cash row
  (`PaymentService.voidLatestCashPayment`: status **`cancelled`**, never a platform
  refund) and recomputes `paid_amount` / `payment_status` (the booking's own status stays);
  undoing `not_paid` only clears the mark. No mark: **409 "This booking has no remainder
  mark"**.
- `DELETE /api/admin/bookings/{id}/remainder-mark` — an admin, any time, same effect;
  answers the `AdminBookingResponse`.
- Both marks apply to a `confirmed`/`completed` booking that is `partially_paid`, or a
  manual (walk-in) booking still `unpaid`. Otherwise **409**: a pending, cancelled, expired
  or game booking (its teams' remainders go through `POST /api/games/{id}/cash`) — "This
  booking has no part payment to complete"; nothing left (paid in full) — "Already paid".
  These were 400 on the cash endpoint before. Cash also answers 409 "A payment for this
  booking is being processed" while a card payment is `processing` / `capture_pending`.
  Not the stadium's owner **403 "You do not own this stadium"**; 404 "Booking not found".
- **A walk-in's payment** (`POST /api/owner/stadiums/{id}/bookings`): optional `price` (tiyin,
  1 … `PRICE_MAX`; absent = the owner's price for that hour, the same figure
  `GET /api/owner/stadiums/{id}/bookings/suggested-price?startsAt=` answers for the form) and
  `paymentStatus` `unpaid` (the default: players pay after the game) or `paid`. `clientPhone`
  is `+998XXXXXXXXX`. Added as `paid`, it records the cash row and the `cash_received` mark, so
  the owner can undo a slip the same day like any other mark. Walk-ins never touch the platform
  or a card; they count in the owner's `paidInCash` / `outstanding` figures.
- Audited: `CASH_RECEIVED` (with `remainderMark`), `REMAINDER_NOT_PAID` (OWNER),
  `REMAINDER_MARK_UNDONE` (OWNER, or ADMIN for the admin's undo; `remainderMark`,
  `paidAmount`, `paymentStatus`, `cashPaymentStatus`). Paying the rest in the app records
  the cleared mark in `BOOKING_REMAINDER_PAID`.
- A voided cash row counts nowhere: not in `paidInCash`, `cashReceived`, `ownerReceives`,
  the owner stats' `…PaidInCash`, nor revenue; the booking is back on the unpaid list.

- **`PaymentService.applySuccessfulBookingPayment` is the only path from "money
  arrived" to "booking confirmed"** — the synchronous charge uses it today and a
  provider webhook will use the same method. If the hold already expired when
  the money lands, it confirms the booking only when nothing else has taken the
  slot; otherwise it refunds that payment in full and notifies the player. The
  EXCLUDE constraint is the final arbiter and a violation is caught and refunded
  there too, so **an overlap error never travels back to the payment provider**.
  This is about an *ordinary* booking. A **team game** books its own slot in
  `TeamGameService.join`, which sets `paid_amount` from the teams' advances and
  confirms the booking itself, because the money arrived before the booking
  existed and belongs to the teams rather than to the booking.

## Cancellation Policy (configurable per stadium)

Cancellation is **all or nothing** — there are no partial refunds, because ATMOS
cannot refund part of a transaction. Each stadium has one setting,
`cancellation_hours` (default 24):

```java
deadline = booking.startsAt - stadium.cancellationHours;

if (now <= deadline) → every card payment of the booking refunded in full
else                 → the booking is still cancelled, but nothing is refunded

// Owner cancels → ALWAYS every card payment back, whatever the time left
// Admin blocks the stadium → same as an owner cancellation
// Cash is NEVER refunded through the platform; the owner settles that directly
```

- **One method does every refund**: `PaymentService.refundInFull(payment, reason)`,
  driven for a whole booking by `refundAllCardPayments(bookingId, reason)`. There
  is no method that refunds an arbitrary amount, so a partial refund cannot be
  written by accident. A failed row, a cash row and an already-refunded row are
  all skipped.
- **A refund ATMOS declines never undoes the cancellation.** The refund row is still
  written, `status = failed` with the provider's message and code in `failure_reason`;
  the payment it was to give back becomes `refund_failed` (the money is still owed);
  audit `REFUND_FAILED` (SYSTEM). An admin then retries it or marks it refunded by hand
  (see "Admin panel API → Failed refunds"). A lost answer is different: the row stays
  `return_pending` for the reconcile job. A **hold** release ATMOS refuses is not a failed
  refund (no money left the card, and the hold lapses on its own): it is logged and left
  `return_pending` for the job.
- Booking responses carry **`cancellationDeadline`** and
  **`refundIfCancelledNow`** (tiyin). Both come from `CancellationPolicy` and the
  same refundable payments that a real cancellation uses, so the figure a player
  is shown is the figure they get. `BookingResponseFactory` fills them in.
- `cancellationPolicy` on a booking or stadium response is just
  `{ "cancellationHours": 24 }`.
- The default of **24 lives in exactly two places that agree**: the
  `stadiums.cancellation_hours` column default and the `Stadium` entity field.
  There is no `app.cancellation.*` config — don't reintroduce one (nor a
  `platform_settings` column for it). The minimum booking amount's platform default is
  `platform_settings.default_min_booking_amount` (T1, changeset 051).
- **Who sets the booking terms**: the owner never does (since the add-stadium rework).
  `cancellation_hours` and `min_booking_amount` are agreed with the platform by phone and
  set by an admin: `PUT /api/admin/stadiums/{id}/booking-terms`
  `{ minBookingAmount: Long|null, cancellationHours: Integer }` — the whole terms each
  time: `minBookingAmount` null (or absent) = **the platform default** (T2), else ≥ 50 000
  tiyin ("minBookingAmount must be at least 50000 tiyin (500 so'm)"), ≤ `pricePerHour`
  ("minBookingAmount must not be more than pricePerHour") and more than the team-game fee
  ("minBookingAmount must be more than the team game platform fee (25 000 so'm)"); the
  responses' `playerMinBookingAmount` is the effective minimum's; `cancellationHours` required
  ("cancellationHours is required"), 0–168 ("cancellationHours must be between 0 and
  168"). Any stadium status; the row is locked. Audited `STADIUM_BOOKING_TERMS_CHANGED`
  (ADMIN, `minBookingAmount` / `cancellationHours` old → new, nothing when unchanged);
  answers the admin stadium detail, "Booking terms updated"
  (`AdminStadiumService.updateBookingTerms`, `AdminStadiumBookingTermsTest`).

Players MUST see the stadium's cancellation policy before confirming a booking.

## Recurring Bookings (booking_series)

- Player says "repeat every Thursday 20:00" — one hour each time, like every booking
- Creates a `booking_series` row + bookings for the next 2 weeks
- `BookingSeriesJob` runs daily, generates next week's booking when the window needs extending
- Each generated booking gets the CURRENT price (price changes apply to future bookings)
- Cancelling one booking: only that row cancelled, series stays active
- Cancelling the series: `is_active = false`, all future bookings cancelled

