# Team vs team: minimum / full payment per team — design

Date: 2026-11-06 · Repos: `minipolyaBackend` first, then `minipolyaFrontend`. No stop-and-ask; decisions logged.
Builds on the existing team-game money flow (hold → join charge → capture, reconcile/resume, cancel bands,
`CardFeeSplit.withPlatformFee`, pay-remainder, per-team cash) and the booking remainder marks.

## Example (fee 25 000, card fee 1.5 %, step 5 000 soʻm)
Pitch price P = 200 000 (owner, net), stadium minimum M = 50 000, team fee F = 25 000.

| Per team | Net | Player sees | Platform nets | Owner part (net) | Remainder (player) |
|---|---|---|---|---|---|
| minimum | M = 50 000 | 55 000 | 25 000 | 25 000 | 130 000 − 55 000 = 75 000 |
| full | P/2 + F = 125 000 | 130 000 | 25 000 | 100 000 | 0 |

## Decision log

| # | Decision | Why |
|---|---|---|
| T1 | New `platform_settings.default_min_booking_amount` = 5 000 000 tiyin (changeset 051), editable in `PUT /api/admin/settings`. **Effective minimum** `M = stadium.minBookingAmount ?? default`. A stadium value ≤ the team fee is ignored at runtime (default used) — no data rewrite. | "Every stadium has a minimum"; existing stadiums without one use the default. |
| T2 | Validation: admin booking-terms minimum must be > `team_game_platform_fee` → else 400 "minBookingAmount must be more than the team game platform fee (25 000 so'm)"; settings refuse `default_min_booking_amount ≤ team fee` and `team fee ≥ default minimum` with the same kind of message. In booking-terms, `minBookingAmount: null` now means "use the platform default" (there is no full-payment-only stadium any more). | User rule. |
| T3 | Ordinary booking unchanged: deposit = `min(playerAmount(M), D)`, remainder = D − paid. With every stadium now having M, availability always returns `depositAmount`/`remainderAmount` unless the deposit ≥ the slot price (then full only). | User rule; verify. |
| T4 | Team payment per team: `minimum` net = M (F to platform + M − F to owner); `full` net = P/2 + F (the odd tiyin of an odd P to side B, as today). If M ≥ the full net, only `full` is offered. Player amount = `playerAmount(net)`; remainder (player) = `playerAmount(fullNet) − paid`, never rounded on its own, 100 % owner. The platform split of every team payment is `withPlatformFee(F)` so it nets exactly F after ATMOS; the owner split is the rest (incl. rounding surplus) — so with both teams on `full` the owner nets ≥ P. | User rules. |
| T5 | `game_teams` gains `payment_option` (`minimum`/`full`, NULL for legacy rows) and the remainder-mark columns (`remainder_mark`, `remainder_marked_at`, `remainder_marked_by`) — changeset 052. Legacy games (payment_option NULL) keep their stored advance/remainder amounts and behaviour. | Never rewrite money already taken. |
| T6 | Create `POST /api/games` (team) and join gain `paymentOption` (`MINIMUM`/`FULL`, uppercase like bookings); absent → `MINIMUM` (old clients behave as before: a part payment). Creator's chosen amount is HELD, joiner's charged, then the creator's hold captured — all rollback/reconcile/resume protections kept. | User rule. |
| T7 | **Quotes** (player amounts only): `GET /api/stadiums/{id}/team-game-quote?startsAt=` (create; R33) and `GET /api/games/{id}/join-quote` (join, for the free side) → `{ options: [{ option: "minimum", amount, remainderAmount }, { option: "full", amount, remainderAmount: 0 }], cancellationDeadline }`. 400 for a non-bookable hour, 404 unknown. | User API item. |
| T8 | `GameChallengeResponse.amountToPayNow` = the minimum option's amount; new `fullAmount`. | Home carousel / board keep working. |
| T9 | Team responses (`GameTeamResponse`): `paymentOption`, `paidAmount` (player money held or arrived), `remainingAmount`, `remainderMark`, owner `remainderMarkUndoableUntil`; admin adds `platformFee`, `ownerPart`, `atmosFee` (existing admin fields stay). | User API item. |
| T10 | Marks for game teams reuse the booking rules: existing `POST /api/games/{id}/cash {side}` also sets `cash_received`; new `POST /api/games/{id}/not-paid {side}`, `DELETE /api/games/{id}/remainder-mark?side=`, admin `DELETE /api/admin/games/{id}/remainder-mark?side=`. Undo until end of that Tashkent day, then admin only; 403 not the owner; 409 cancelled/nothing remaining; audited (`CASH_RECEIVED`, `REMAINDER_NOT_PAID`, `REMAINDER_MARK_UNDONE`). Undo of cash voids that cash row (status `cancelled`). Paying the rest in app clears `not_paid`. | User rule; reuse. |
| T11 | Cancellation/refund bands unchanged, applied to what each team actually paid. | User rule. |
| T12 | Mini App: create ("Raqib qidirish") and accept screens drop "Maydon narxi"; two choice cards from the quote (reuse the checkout `PaymentOptions` look): "Minimal toʻlov: 55 000 soʻm" + "Qolgan 75 000 soʻmni keyin ilovada yoki naqd toʻlaysiz", "Toʻliq toʻlov: 130 000 soʻm". Game screen "Jamoangiz toʻlovi": "Toʻlangan: X" (also while held) + while waiting "Raqib topilmasa, toʻliq qaytariladi."; "Qolgan summa" + "Ilovada toʻlash" + "yoki maydonda naqd" only when > 0; "Toʻliq toʻlangan" / "Naqd olindi" / "Toʻlanmadi". Owner Jadval team games: per team the labelled amounts and both marks (same components as bookings). Admin game panel: per team option, paid, remainder, split. R33 removed from `missing.ts`. | User Mini App items. |

## Testing
Property-style tests over many P (incl. odd) and M: platform nets exactly F per team payment for both
options; owner nets ≥ P when both pay full; remainder = full − paid; amounts multiples of the step; M ≤ F refused.
Existing team-game tests (holds, capture, rollback, reconcile, bands) keep passing. Chrome check of the five flows.
