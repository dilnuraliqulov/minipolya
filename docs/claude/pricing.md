# Pricing, the player price and platform settings

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching any amount a player sees, the card fee, rounding, the minimum booking amount or platform settings.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Platform Settings

Platform-wide money rules live in the single-row `platform_settings` table, not in
config. Admins read and change them at `GET` / `PUT /api/admin/settings`.

| Setting | Default | Meaning |
|---|---|---|
| `team_game_platform_fee` | 2 500 000 tiyin (25 000 so'm) | Charged per team on a team game — the platform's only money |
| `casual_game_service_fee` | 0 | Charged per player on a casual game |
| `card_fee_bps` | 150 (1.5%) | The fee ATMOS takes; player prices are grossed up by it (0–1000) |
| `payment_rounding_step` | 500 000 tiyin (5 000 so'm; changeset 049 — 10 000 before) | Player prices are rounded up to a multiple of it (1–1 000 000) |
| `default_min_booking_amount` | 5 000 000 tiyin (50 000 so'm; changeset 051) | The minimum booking amount of every stadium without its own (T1); API `defaultMinBookingAmount` |

- There is **no booking commission**: the platform takes nothing from ordinary
  bookings. `booking_commission_percent` was dropped in changeset 040; old payments
  keep their stored `commission_percent`.
- The row is seeded by changeset 021; `singleton` is UNIQUE and CHECKed true, so a
  second row is impossible.
- Validation: fees not negative, card fee 0–1000 bps, rounding step 1–1 000 000 tiyin;
  the default minimum must stay **above the team fee** (T2), checked on the values the row
  would have: "defaultMinBookingAmount must be more than the team game platform fee
  (25 000 so'm)" / "teamGamePlatformFee must be less than the default minimum booking amount
  (50 000 so'm)". A rejected value writes nothing.
- Every change writes one `SETTINGS_CHANGED` audit entry listing each changed
  field with its old and new value (see "Audit log").

## The player price

**The owner enters their own price; players see ONE price.** The owner's price (`N`,
`stadiums.price_per_hour`, pricing rules, `min_booking_amount`) is stored and edited as
the owner typed it. Players see and pay one price per amount — never the words or
fields "card fee", "commission", "net" or any breakdown. **One helper converts**,
integer math only (`PriceCalculator.playerAmount`, applied with the settings in force by
`CardPricing.playerAmount`); nothing else calculates it:

```
D = playerAmount(N) = roundUp( ceil(N * 10000 / (10000 - card_fee_bps)), payment_rounding_step )

step 5 000 so'm:  150 000 → 155 000;  160 000 → 165 000;  197 000 → 200 000;  200 000 → 205 000
step   100 so'm:  150 000 → 152 300
```

**The platform takes nothing from ordinary bookings.** Every booking card payment —
full, deposit or remainder — is 100% the owner's split (`CardFeeSplit.toOwner`, platform
share 0). ATMOS takes its fee from it; the rounding remainder is the owner's. Example:
owner price 150 000 → the player pays **155 000** (step 5 000) → the owner receives
**152 675** (155 000 less 1.5%).

| Payment | Player pays | Split |
|---|---|---|
| Booking, in full | **D** = the sum of each hour's `playerAmount`, so the slot prices on screen add up exactly; stored as `bookings.card_price` | all the owner's |
| Booking, deposit | **d** = `min(playerAmount(M), D)`, M the effective minimum (50 000 → 55 000 at a 5 000 step) | all the owner's |
| Booking remainder | **r = D − d**, the same amount in the app or in cash | all the owner's |
| Manual (walk-in) booking | the owner's own price, no conversion — cash between them | — |
| Team game, per team, on joining | `minimum`: `playerAmount(M)`; `full`: `playerAmount(P/2 + fee)` (older teams: `playerAmount(advance + fee)`) | platform `ceil(fee × 10000 / (10000 − bps))` — nets exactly the fee; the rest, rounding included, the owner's |
| Team remainder | `playerAmount(P/2 + fee) − paid`, the same in the app or in cash (older teams: `playerAmount(remainder)`) | all the owner's |
| Friends game, per player | `playerAmount(pitch share + service fee)` | all the owner's; a service fee > 0 is split like the team fee |

Cash goes to the owner in full. A full refund returns everything the player paid by
card. Existing bookings and payments keep their stored amounts (`pricing_model = net`).

ATMOS takes its rate on **each split**, rounded per split: what a recipient keeps of a
split `g` is `PriceCalculator.netAfterFee(g) = floor(g × (10000 − bps) / 10000)`, the
inverse of `grossUp`. So the platform's team-fee split nets exactly the fee; with that
rounding the owner's share of a team payment can come out at most 1 tiyin under the
advance (`PlayerPriceRuleTest`).

## API fields

Every amount a player sees is the final amount they pay.

- **Player** (public stadium list/detail, availability slots, public pricing rules, the
  player's own bookings, games and payments): `pricePerHour` (a slot's `originalPrice`
  too) and a stadium's `minBookingAmount` are player prices; a booking's `price` is D,
  `paidAmount` what was paid of it, `remainingAmount` what is left;
  `refundIfCancelledNow` is everything paid by card; `paidInCash` the cash the owner took
  that still counts, and `remainderMark` the owner's mark (`cash_received` / `not_paid`,
  absent when none). A free availability slot carries
  `depositAmount` (`CardPricing.deposit` of the effective minimum, exactly what a MINIMUM
  booking charges) and `remainderAmount` (`pricePerHour − depositAmount`), absent only when
  the deposit is the whole price (T3). Games: `totalPrice` is what the
  players pay altogether, a friends game's `price` one share, a team's `price` its
  joining payment and `remainderAmount` its remainder; the challenge board's
  `amountToPayNow` / `remainderAmount` likewise. No breakdown field exists in these
  views; `PriceFieldVisibilityTest` fails on one.
- **Owner**: `pricePerHour` / `minBookingAmount` are the owner's own (what they edit),
  with `playerPrice` / `playerMinBookingAmount` alongside. A booking shows `price` (what
  the player pays), `paidAmount` and `remainingAmount` (the remainder to collect in cash)
  **exactly as the player sees them**, plus `ownerPrice`, `paidInApp` and `ownerReceives`
  (card splits after ATMOS, plus cash), `paidInCash`, `remainderMark`, `remainderMarkedAt`
  and `remainderMarkUndoableUntil` (the end of the mark's Tashkent day; the admin view
  has the mark fields but not this one).
- In the player, owner and admin booking views alike, **`paidAmount + remainingAmount =
  price` for every booking**: a `net` booking (made before changeset 040, and a game
  booking with a team from before changeset 052) keeps its stored owner's-terms amounts, and `BookingResponseFactory` converts
  them the one way (`CardPricing.playerPrice` / `remainingForPlayer`) for every view.
- **Admin**: the owner's view plus `ownerReceives` and `atmosFee` on stadiums, pricing
  rules and bookings; a team adds `platformFee`, `ownerPart`, `ownerAdvance`, `ownerRemainder`,
  `ownerReceives`, `atmosFee`; a payment shows `ownerAmount`, `platformAmount`,
  `atmosFee` (`card_fee_expected`), `providerCommission` and what each side keeps after ATMOS,
  `ownerNet` / `platformNet` (`util/PaymentNets`, read from the stored row;
  `ownerNet + platformNet + atmosFee = amount`, and `platformNet` is exactly the team fee).
- The public search's `maxPrice` is a player price, converted to the largest fitting
  owner price.

