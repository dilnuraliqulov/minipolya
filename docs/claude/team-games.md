# Team games (team_vs_team)

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching team games: holds, joins, the two deadlines, the cancellation bands, remainders or the challenge board.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

**team_vs_team — team vs team:**

All of this lives in `service/TeamGameService.java`, not in `GameService`, which
only keeps the shared create/join/cancel entry points and delegates when
`type = team_vs_team`. `creator_share_pct` is gone — the column was dropped in
changeset 025 and there is no percentage split any more.

*States.* Three of them, and the first is persisted as the existing
`games.status = 'open'` — the value was deliberately **not** renamed, because
casual games share the column:

| State | `games.status` | The slot |
|---|---|---|
| WAITING | `open` | **not booked** — free for everyone, the one team's money only on hold |
| CONFIRMED | `confirmed` | a `source = game` booking exists |
| CANCELLED | `cancelled_slot_taken` / `cancelled_by_creator` / `cancelled_not_filled` | free |

A CONFIRMED game can go **back to WAITING** when one team leaves. Its booking is
cancelled and a new one is created when the next opponent joins.

*Money: minimum or full, per team (T4–T6, `util/TeamPaymentQuote`).* For pitch price `P`
(the game's `total_price`, owner's terms), the stadium's **effective** minimum `M` (its own,
or the platform default — see "Paying for a booking") and the team fee `F`
(`platform_settings.team_game_platform_fee`), all tiyin, each team chooses on creating or
joining (`paymentOption`, `MINIMUM` when absent):

| Option | Net | Player pays now | Platform | Owner part (net) | Left to pay (player) |
|---|---|---|---|---|---|
| `minimum` | `M` | `playerAmount(M)` | `F` | `M − F` | `playerAmount(P/2 + F) − paid` |
| `full` | `P/2 + F` | `playerAmount(P/2 + F)` | `F` | `P/2` | 0 |

**Example** (fee 25 000, card fee 1.5 %, step 5 000 so'm; P = 200 000, M = 50 000): minimum
**55 000** now and **75 000** later (130 000 − 55 000), platform 25 000, owner part 25 000;
full **130 000**, platform 25 000, owner part 100 000. In tiyin 5 500 000 / 7 500 000 /
13 000 000.

- The odd tiyin of an odd `P` goes to side **B** (`P/2` is `P − P/2` there).
- `minimum` is offered only when `F < M < P/2 + F` and its player amount is below the full
  one; otherwise only `full` is, and a `MINIMUM` request is simply taken as `FULL`.
- The remainder is **never rounded on its own**: it is the full player amount less what was
  paid, the same in the app and in cash, and **100 % the owner's** (`CardFeeSplit.toOwner`).
- Every joining payment is split `CardFeeSplit.withPlatformFee(paid, F)`: the platform's
  split is `ceil(F × 10000 / (10000 − card_fee_bps))`, so after ATMOS it nets **exactly F**;
  everything else, rounding included, is the owner's — so with both teams on `full` the owner
  nets at least `P` (`TeamPaymentQuoteTest`, a property test over 20 000 prices, minimums,
  fees, steps and card fees). The fee is the platform's **only** money in a team game.
- Stored per team on `game_teams` (changeset 052): `payment_option`, `join_price` (held or
  charged), `remainder_price` (left), and in the owner's terms `fee_amount` = F,
  `advance_amount` = net − F, `remainder_amount` = full net − net. `CardPricing.teamJoinPrice` /
  `teamRemainderPrice(GameTeam)` read them.
- **Rows from before changeset 052** (`payment_option` NULL) keep their stored amounts and the
  old rule (`util/TeamGameSplit`: each paid `playerAmount(fee + base/2)`, remainder
  `playerAmount((P − base)/2)`); nothing about them is recomputed.
- **Quotes** (T7), player amounts only, `TeamGameQuoteResponse { options: [{ option:
  "minimum", amount, remainderAmount }, { option: "full", amount, remainderAmount: 0 }],
  cancellationDeadline }` — `minimum` listed only when offered: `GET
  /api/stadiums/{id}/team-game-quote?startsAt=` (public, creating, side A; 400 for an hour
  that cannot be booked — past, off the hour, outside opening hours, closed, taken; 404
  unknown stadium) and `GET /api/games/{id}/join-quote` (the free side; 404 unknown; 400 not
  a team game / not waiting / past the 3-hour point).
See "Payments → Team game money → ATMOS operations" for holds, captures and reversals.

*The two deadlines.*
- **the deadline** — `starts_at − stadium.cancellation_hours` (default 24).
- **the 3-hour point** — `starts_at − app.game.opponent-deadline-hours`
  (default 3). After it nobody may join and a game still without an opponent is
  over. **team_vs_team only** — casual games still fill right up to kick-off.

*Creating (WAITING).* The creating leader picks a saved card and an option; the chosen
amount goes on **HOLD** (`payments.status = held`, type `team_game`). No slot is booked. A
declined card means no game at all.

*Joining (→ CONFIRMED).* Order matters, every failure leaves nobody paying for a game
that will not happen, and **each step commits on its own** (`TeamGameService.join`,
deliberately not one transaction — `GameService.joinGame` is not `@Transactional`), so a
crash or an unclear answer half-way leaves a record the reconcile job finishes, never
money that moved with no row for it:
1. after the 3-hour point → 400 `O'yin boshlanishiga 3 soatdan kam qoldi, qo'shilib bo'lmaydi`;
2. **step 0, committed before any provider call**: the joining team as
   **`joining`** (changeset 046; it holds its side — the partial unique index on
   `(game_id, side)` covers `active` and `joining` — so a second opponent is rejected
   before any card is touched), the slot as a pending `source = game` booking, and the
   joiner's `processing` payment row. If the EXCLUDE constraint rejects the slot nothing
   is committed and nobody is charged;
3. charge the joining team: created (its transaction ids committed), then confirmed. A
   decline drops the join (team `cancelled`, booking deleted) and the game goes on
   waiting; no answer drops it the same way and the reconcile job gives the money back
   if it was taken; an unreachable provider → 502;
4. capture the waiting team's hold, last: the hold is marked `capture_pending`
   (committed), then captured;
   - **captured** → the team becomes `active`, the booking and game confirmed, the
     leader's participant row, notifications and audit written;
   - **refused, with any code** (lapsed, cancelled, or any other refusal) → **undone**:
     the decision is committed first (game `cancelled_slot_taken`, audit reason naming
     the provider's code, every team out, booking deleted, every payment to give back
     `return_pending`), then the joiner is reversed and the hold released, each in its
     own step; both teams notified; 400 "The other team's payment could not be taken, so
     the game is cancelled; your payment is being returned in full";
   - **no clear answer** (timeout, provider unavailable) → left `capture_pending` and
     answered **202 `PAYMENT_PENDING`**; the reconcile job finishes it.

`TeamGameService.resumeJoin` drives a join from wherever it stands, and is what the
reconcile job runs (`GameService.resumeStuckJoins`) for every team still `joining` after
five minutes, once that run's payments are settled: the joiner's charge unclear → wait;
not charged → dropped; the game ended meanwhile (its 3-hour point, its leader, a booking)
→ undone, the joiner refunded; the capture `captured` → confirmed; `released` or refused
→ undone. Every step re-reads and locks its rows, so running it twice is safe.

The joining team takes whichever side is **free**, not always B: when the
creating team walks out of a confirmed game the team left behind is on side B,
so the next opponent takes A. **The server decides the side**, never the client:
`POST /api/games/{id}/join` on a team game takes `teamId` + `cardId` from that team's
**leader** only (anyone else, or no `teamId` → 403 "Only the joining team's leader can
join a team game, with their team and a card"; no card → 400), and the leader always
pays. A sent `side` is optional; `"b"` is still accepted from older clients, any other
side that is not the free one → 400 "The opposing team joins as side b". Confirming also clears any **other** team still
waiting on that hour, the same rule an ordinary booking follows — otherwise
their money would sit on hold for a pitch that is already gone.

The game's booking (`TeamGameService.refreshBookingPayment`): when **every** team in the
game chose an option, it counts in player terms — `pricing_model = player_price`,
`card_price` = what both teams pay altogether (each `join_price + remainder_price`) and
`paid_amount` = what they paid of it — so its `remainingAmount` (owner schedule, unpaid list,
stats' outstanding) is exactly the teams' unpaid rests; `paid` once nothing is left (both
on `full`, or both rests settled). Otherwise (a team from before changeset 052) it keeps the
old `net` accounting: the sum of the teams' advances plus any settled remainder.
Its **money fields** come from the teams' payments, since none sit on the booking
(`BookingResponseFactory.moneyPayments` → `PaymentRepository.findTeamPaymentsOfGameBooking`:
the payments of the game's `active` teams, the ones `paidAmount` counts), in the owner and admin
views alike: `paidInApp` = their card money that arrived (`paid` / `captured` / `completed`; a
hold counts only once captured — on a confirmed game both joining payments are, so it agrees
with `paidAmount`), `paidInCash` and the admin's `cashReceived` = their cash not voided,
`ownerReceives` = owner splits after ATMOS plus that cash, `atmosFee` ATMOS's part.
`paymentState`, `declineReason` and `refundIfCancelledNow` stay the booking's own, and its
`remainderMark` stays absent (marks are per team). Only `player_price` game bookings with team
payments; a `net` (legacy) game booking keeps its own (zero) figures. `TeamGameBookingMoneyTest`.

*Everything back / nothing back.* "Everything back" releases a `held` payment
(no refund row — no money ever moved) and refunds a settled one in full;
"nothing back" captures a `held` payment and keeps the rest. One pair of methods:
`PaymentService.giveBackAllTeamPayments` / `keepAllTeamPayments`, previewed
without moving anything by `refundableTeamTotal`.

*A WAITING game ends without an opponent* — the waiting team gets everything back
in all three cases:

| Trigger | Status |
|---|---|
| someone books the slot (app or manual) | `cancelled_slot_taken` |
| the 3-hour point passes (`GameTimeoutJob`) | `cancelled_not_filled` |
| its leader cancels **before the deadline** | `cancelled_by_creator` |
| its leader cancels **after the deadline** | `cancelled_by_creator`, **nothing back** |
| its leader cancels **past the 3-hour point** | `cancelled_not_filled`, everything back — the job would have refunded them within the minute, so racing it must not cost them |

*A CONFIRMED game: either leader may cancel* (not just the creator), and the
booking is released as **`cancelled_opponent_left`** so the owner's schedule
says why the pitch is free again rather than blaming a player or the owner.

**Nothing is cancellable once kick-off has passed**, waiting or confirmed. The
pitch was there to be used, and nothing sets `games.status = 'completed'` yet, so
without that guard a played game stays `confirmed` and the losing leader could
reverse its booking and have the opponent refunded for a game they actually got.

| When | The canceller | The other team | The game |
|---|---|---|---|
| before the deadline | everything back, remainder included | keeps its payments, stays in | back to **WAITING** |
| after the deadline, before the 3-hour point | nothing back | keeps its payments, stays in | back to **WAITING** |
| past the 3-hour point | nothing back | **everything back** | `cancelled_not_filled` |

The bands are evaluated with the 3-hour rule **first**, so they still behave on a
stadium whose `cancellation_hours` is under 3, where the two would otherwise
overlap and contradict each other.

The owner cancelling the game's booking (`BookingService.cancelByOwner`), or an
admin blocking the stadium, goes through `GameService.cancelGameForBooking` →
`TeamGameService.cancelForBookingCancelled`: **both** teams get everything back
whatever the window says, and the game ends. Refunding the booking's own payments
would hand back nothing, because a team game's money never sits on the booking.

*Remainder, per team.* Two separate debts. Each leader settles their own from a
saved card at `POST /api/games/{id}/pay-remainder` (before or after the game; 400
"Already paid" when the team owes nothing — paid in full, or settled). The owner takes
either team's in cash at `POST /api/games/{id}/cash` `{"side":"a"}`. Whichever lands first
wins. The booking reaches `paid` only once **both** teams currently in the game have
settled.

*Owner marks on a team's remainder (T10)* — the booking rules ("Paying for a booking →
Owner marks") on `game_teams.remainder_mark` / `_marked_at` / `_marked_by`:
- `POST /api/games/{id}/cash {side}` records the cash (the team's `remainder_price`) **and**
  sets `cash_received` (replacing `not_paid`); "Cash recorded".
- `POST /api/games/{id}/not-paid {side}` sets `not_paid` — no charge, no penalty; again changes
  nothing; "Marked as not paid". Paying the rest in the app **clears** it (`settleRemainder`,
  also after a late answer); cash replaces it.
- `DELETE /api/games/{id}/remainder-mark?side=` — the owner's undo until the end of the
  Tashkent day of the mark (`BookingService.markUndoableUntil`), later **409 "Only an admin
  can change this mark"**; `DELETE /api/admin/games/{id}/remainder-mark?side=` — an admin, any
  time (answers the admin game detail). Undoing `cash_received` voids that team's latest cash
  row (`PaymentService.voidLatestTeamCashPayment`: `cancelled`) and the rest is owed again;
  "Mark undone". No mark **409 "This team has no remainder mark"**.
- Cash and not-paid: **403** "You do not own this stadium"; **409** "This game is cancelled",
  "This game is not confirmed yet" (waiting), **"Already paid"** (nothing left — this was 400
  on the cash call before), "A payment for this team is being processed"; 404 "No team is on
  side a".
- Audited on the game: `CASH_RECEIVED` (`teamX.remainderStatus`, `teamX.remainderMark`,
  `teamX.cashAmount`), `REMAINDER_NOT_PAID`, `REMAINDER_MARK_UNDONE` (OWNER / ADMIN;
  `teamX.remainderMark`, `teamX.remainderStatus`, `teamX.cashPaymentStatus`),
  `GAME_REMAINDER_PAID` (with the cleared mark).

*What a team sees.* `GameDetailResponse.teams` carries, per team, in player prices:
`paymentOption` (`minimum` / `full`; absent on older teams), `price` (paid or held on
joining), `remainderAmount` (its rest, the same in the app or in cash), `remainderStatus`,
`paidAmount` (held or arrived, card or cash, a voided cash row excluded), `remainingAmount`
(`remainderAmount` while unpaid, else 0), `remainderMark` / `remainderMarkedAt`, for the
stadium's owner `remainderMarkUndoableUntil`, `paidInApp`, `heldAmount` (tiyin on hold on the
team's card now: a hold `held` or `capture_pending` that has not lapsed,
`PaymentService.heldTeamTotal`; 0 otherwise), `cancellationDeadline` and
`refundIfCancelledNow` (tiyin, 0 when nothing would come back) — no fee or advance. The
admin's game detail adds `platformFee`, `ownerPart` (the owner's net part of the joining
payment), `ownerAdvance`, `ownerRemainder`, `ownerReceives` and `atmosFee`.
`refundIfCancelledNow` is computed by the same code that performs the
cancellation, so the figure shown is the figure paid; the cancellation bands are unchanged
and apply to what each team actually paid (T11). Note the asymmetry it
answers: past the 3-hour point a team's own figure is 0 because cancelling
forfeits, yet that same team is made whole if its *opponent* cancels.

*Finding opponents.* `GET /api/games/challenges` (filters `date`, `format`,
`stadiumId`, `level`; paged) lists WAITING team games before their 3-hour point, excluding
any the caller's own team is waiting on (membership and leadership both count), and
any another team is half-way through joining (R45: a `joining` team, e.g. after 202
`PAYMENT_PENDING` — left out for everyone, the joining captain too; back on the board
if that join fails). `GameDetailResponse.joinPending` (team games only) is true meanwhile.
Each item carries `amountToPayNow` = the free side's **minimum** option (T8; the full one
when no minimum is offered), `remainderAmount` = what is left after it, `fullAmount` = the
full option, and `totalPrice` = what both teams pay altogether (a team in counts what it
fixed, a free side in full), plus `stadiumDistrict` and `stadiumPhotoUrl` (E9: the cover photo, absent
when none; the page's covers in one grouped query, `GameRepository.findStadiumPhotoUrls`).
**Where the game is played:** each board item and every `GameDetailResponse` (any game type,
anyone who opens it, in every status) carry `location` — `StadiumLocationResponse` from
`StadiumMapper.toLocationResponse`: `id`, `name`, `address`, `district`, `landmark` (Moʻljal),
`latitude`, `longitude`, `coverPhotoUrl`, `format` (absent fields omitted). The board fetches
the stadiums with its page (`@EntityGraph` on `findChallenges`), so no stadium loads per card
(`TeamGameLocationTest`).
`level` (`havaskor` / `orta` / `professional`, changeset 054) narrows the board to the games
whose **waiting team** is of that level, **in the query itself** — a page is never sliced
after the fact. An unknown value is a 400 before anything is read; leaving it out lists every
level, teams without one included. It is switched off by an `anyLevel` flag with a sentinel
level rather than a null bind, the same reason `stadiumId` and `format` are (see
`GameRepository.findChallenges`). Each card carries `waitingTeamLevel`, and each side of a
game carries `teamLevel`, so the badge is shown without another request. The level never
decides who may join — the app only asks a captain to confirm a mismatch.
A team game
stays `is_public = false`; this endpoint queries by type and status instead.
Availability and the owner's schedule mark a slot where a team is looking for an
opponent — **only** team_vs_team, never casual.

*Chat* is open exactly while the game is CONFIRMED, for reading as well as
sending. A leader whose team joined partway through sees only messages sent
after they joined. A team that **left** loses the chat entirely: its players are
marked `cancelled` with it, so a departed leader cannot read what the teams
still playing say afterwards. An invited member leads no team, so they keep
their side's whole history.

- **System lines (changeset 055).** The platform writes in the chat too: when a game is
  confirmed it says `game_confirmed`, then `team_joined` naming the team that took the free
  side. They are ordinary `game_messages` rows with `kind = 'system'`, **no sender**
  (`user_id` is nullable from 055) and the event in `event` / `event_team_name`, so they
  page, sort and get cut at a late joiner's join like everything else, and the clients word
  them in the reader's own language. `GameMessageService.systemMessage` is written in the
  confirming transaction and never throws at it — a game has just been confirmed and money
  has moved.
- **Who is speaking.** Every message carries its sender's `side`, `teamId`, `teamName` and
  `teamLevel`, resolved once for a whole page from the game's participants and its active
  teams — never once per message. A sender in neither team simply has none.
- **Older messages.** `GET /api/games/{id}/messages` takes an optional `before` (an ISO
  instant): the page of messages strictly older than it, newest first. Without it the
  endpoint behaves exactly as before. It is a cursor rather than a deeper page number
  because a message arriving meanwhile would shift every page boundary under the reader's
  thumb. The joined-at cutoff still applies on top of it.
- **How long a message may be** is the server's: `chatMessageMaxLength` on a team game's
  `GameDetailResponse` (= `SendGameMessageRequest.MESSAGE_MAX`, the same constant its
  `@Size` validates against), so a composer's limit and counter are never a number copied
  into a client.

*Members.* Leaders still invite team members via the link, and members do **not**
pay for the pitch — but a member cannot join a team game through
`POST /api/games/{id}/join` (403); that endpoint is the paying leader's only.

