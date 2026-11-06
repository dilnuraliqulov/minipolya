# Deposit / remainder, owner paid / not-paid, team games on home, 5 000 soʻm rounding — design

Date: 2026-11-06 · Repos: `minipolyaBackend` first, then `minipolyaFrontend`.
No stop-and-ask: every open point is decided below. The survey found most of the flow already built
(paymentOption MINIMUM/FULL, pay-remainder, R37 cash, R38 unpaid list + stats, challenges board);
this work fills the gaps only.

## Decision log

| # | Decision | Why |
|---|---|---|
| E1 | **Rounding:** changeset `049-rounding-5000.sql`: `UPDATE platform_settings SET payment_rounding_step = 500000 WHERE payment_rounding_step = 10000;` and the column DEFAULT → 500000; entity `DEFAULT_PAYMENT_ROUNDING_STEP = 500_000L`. An admin-changed value is kept. | User item 6. |
| E2 | Every player amount stays `playerAmount(x)` (rounded up to the step); **remainder = player price − player deposit** (never rounded on its own), so it is round whenever both are. Tests: owner 150 000 → 155 000; min 50 000 → 55 000; remainder 100 000; team join / remainder amounts multiples of 5 000. | User item 6. |
| E3 | **Per-slot quote on availability:** each free `TimeSlot` gains `depositAmount` and `remainderAmount` (player tiyin) when the stadium has a minimum and the deposit is below the slot price; absent otherwise (full payment only). Computed by the same `CardPricing.deposit` that `POST /api/bookings` charges. R27 quote endpoint is superseded (not built). | User item 1: amounts from the API, not computed in the frontend. |
| E4 | Player `BookingResponse` gains `paidInCash` (tiyin, cash rows that arrived; closes R30) and `remainderMark` (`cash_received` / `not_paid`, absent when none). | User item 2 labels. |
| E5 | **Marks:** new nullable columns on `bookings` (changeset 050): `remainder_mark` VARCHAR(20) CHECK (`cash_received`,`not_paid`), `remainder_marked_at` TIMESTAMPTZ, `remainder_marked_by` UUID. "Naqd olindi" = existing `POST /api/owner/bookings/{id}/cash` (also sets the mark). New `POST /api/owner/bookings/{id}/not-paid` (no charge, no penalty; booking keeps its remainder, player can still pay it). New `DELETE /api/owner/bookings/{id}/remainder-mark` = undo: for `cash_received` the CASH payment row is voided (status `cancelled`, never a platform refund) and the booking's paid amount/status recomputed; for `not_paid` the mark is cleared. | User item 3. |
| E6 | Undo allowed until the end of the Tashkent day of `remainder_marked_at`; later → 409 "Only an admin can change this mark". Admin: `DELETE /api/admin/bookings/{id}/remainder-mark` any time. Every mark and undo audited (`CASH_RECEIVED`, new `REMAINDER_NOT_PAID`, `REMAINDER_MARK_UNDONE`; admin actor for admin undo). | User item 3. |
| E7 | Rules for both marks: not the stadium's owner → 403; cancelled/expired booking or nothing remaining (fully paid in app) → **409** (the existing cash endpoint's 400s for these become 409; frontend `messages.ts` updated). A `not_paid` booking can still be marked cash later (replaces the mark). Paying the rest in the app clears `not_paid`. | User item 3. |
| E8 | Owner and admin booking responses add `remainderMark`, `remainderMarkedAt`, `remainderMarkUndoableUntil` (owner only) and `paidInCash`. Admin Bronlar list + panel show the status chip. | User items 3, 5-admin. |
| E9 | **Challenges:** `GameChallengeResponse` gains `stadiumPhotoUrl` (cover, absent when none) and `stadiumDistrict`; loaded per page in one grouped query. Existing filtering (3-hour rule, joining excluded, own teams excluded) kept. Amount shown = `amountToPayNow`. | User item 4. |
| E10 | **Status chip** (owner Jadval, admin): fully paid in app → "Toʻliq toʻlangan"; `cash_received` → "Naqd olindi"; `not_paid` → "Toʻlanmadi"; else remaining > 0 → "Qoldiq: <amount>". Player side: remaining > 0 → "Qolgan summa" + "Ilovada toʻlash" + "yoki maydonda naqd toʻlang"; paid with cash → "Maydonda naqd toʻlangan"; else "Toʻliq toʻlangan". | User items 2, 3. |
| E11 | **Home carousel** "Raqib qidirayotgan jamoalar" on Maydonlar: horizontal scroll-snap cards (photo, stadium, date+time, waiting team, "Jamoangiz toʻlaydi: X"), hidden when empty or on error, skeleton while loading, "Hammasi" → the full challenge board (a route reachable outside Jamoam). Card tap → `/challenges/:gameId`; captain "Qabul qilish", others "Sardoringiz qabul qila oladi". | User item 4. |
| E12 | Checkout: two choice cards only when the slot has `depositAmount`; else full only, no choice. The frontend's own deposit calculation is removed. Cancellation rule shown before paying (exists). | User item 1. |

## Testing
Backend `./mvnw test` with new tests per rule; frontend unit tests per UI state; Chrome check at
360×640 and 390×844, light and dark, as player, owner and admin.
