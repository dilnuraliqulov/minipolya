# Payments, the provider and reconciliation

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching payments, the ATMOS/fake provider, declines, refunds, holds, saved cards or reconciliation.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Payments

### Why a card payment was refused (R44)

A charge or hold the provider refuses says why, in the player's terms, mapped from the
provider's code in exactly one place, `provider/DeclineReason.of`:

| Reason | ATMOS | Fake card ending |
|---|---|---|
| `INSUFFICIENT_FUNDS` | `STPIMS-ERR-057`, `-112` | `0051` (or the balance spent) |
| `CARD_EXPIRED` | `STPIMS-ERR-067` | `0054` |
| `CARD_DECLINED` | every other refusal — the fallback; an unknown code is logged (the code only) | `0002` |

It is stored on the failed row (`payments.decline_reason`, changeset 047; null when a
payment failed without a refusal, e.g. never acknowledged, or the provider was
unreachable) and shown as:
- the **`code`** of the 400 declines: `POST /api/games` (team game), `POST /api/games/{id}/join`,
  `POST /api/games/{id}/pay-remainder`, `POST /api/games/{id}/pay?cardId=` — `CardDeclinedException`;
- **`declineReason`** on `BookingResponse` (every view): why the booking's latest card
  payment (in full, deposit or remainder) was refused — a declined booking or remainder
  answers 200, so this is where the reason is; absent once a later payment went through;
- **`paymentState`** on `BookingResponse` (every view, always present), from the same
  latest card payment, so **`declined` ⇔ `declineReason` present**: `paid` (its money
  arrived, even if given back since), `processing` (no answer yet), `declined` (refused —
  see the reason), `failed` (failed without a refusal, e.g. never acknowledged, or the
  provider could not be reached — see "A confirmation ATMOS could not answer"), `none`
  (no card payment: cash, manual and game bookings). `BookingResponseFactory.paymentStateOf`.
  The Mini App shows `processing` as "Toʻlov tekshirilmoqda", never as unpaid, and reads the
  booking again every few seconds until it settles;
- **`declineReason`** on `PaymentResponse` (`GET /api/payments/my`) and
  `AdminPaymentResponse`, on a refused row. Never the provider's own code or text.

### The provider

Every flow goes through `PaymentProvider` (`provider/`), shaped around the ATMOS
operations. Amounts are tiyin; **there is no partial refund anywhere**.

| Operation | ATMOS |
|---|---|
| `bindCardInit(cardNumber, expiry)` → bindingId, maskedPhone | `POST /partner/bind-card/init` |
| `bindCardConfirm(bindingId, otp)` → cardToken, cardId, maskedPan, expiry | `POST /partner/bind-card/confirm` |
| `removeCard(cardId, cardToken)` | `POST /partner/remove-card` |
| `charge(splits, token \| number+expiry, account)` → transaction ids, needs OTP without a token | `POST /merchant/bulk/pay/create` + `pre-apply` |
| `confirmCharge(transactionIds, otp)` → successTransIds, commission, ofdUrl | `POST /merchant/bulk/pay/apply` |
| `getCharge(transactionId)` — after a lost answer | `POST /merchant/pay/get` |
| `reverse(successTransId, reason)` — the whole transaction | `POST /merchant/pay/reverse` |
| `hold(splits, cardToken, account, durationMinutes)` → holdId | `POST /hold/multiple/create` |
| `confirmHold(holdId, otp)` | `PUT /hold/multiple/apply/{id}` |
| `captureHold(holdId)` — always the full amount | `POST /hold/{id}` |
| `releaseHold(holdId)` | `DELETE /hold/{id}` (`send_cancel_sms=true`) |
| `getHold(holdId)` | `GET /hold/{id}` |

- A split names the stadium owner's ATMOS recipient (`owner_legal_details.atmos_recipient_id`,
  the only place it is stored — see "Owner legal details") or the platform, whose
  recipient the provider takes from `ATMOS_PLATFORM_RECIPIENT_ID`.
- `account` is our payment row id: the row is written first (`processing`), so a retry
  never charges twice.
- A declined card is a `FAILED` outcome. `STPIMS-ERR-092` (transaction closed) and `-100`
  (already reversed), `-145` (hold cancelled) and `-144` (hold expired) are
  `TransactionClosedException`, `HoldCancelledException`, `HoldExpiredException`; any
  other code is a `PaymentProviderException` carrying it — logged, never shown raw to a
  player. The codes we act on live in `provider/AtmosCodes` (with the ones ATMOS does not
  document marked as ours). No answer is a `ProviderTimeoutException`: the payment stays
  `processing`, never assumed failed, never sent again.
- **A refusal is never a 500.** A charge or hold refused while being created: an
  unreachable provider (`AtmosCodes.isUnavailable`: HTTP 5xx, `STPIMS-ERR-024/-026/-041/
  -094/-102`) is **502** "To'lov tizimi vaqtincha ishlamayapti. Birozdan so'ng qayta urinib
  ko'ring" and nothing is kept; any other refusal (removed card, under the minimum) a
  `failed` row with `failure_reason`. Refused after creation: left `processing` for the
  reconcile job. Card binding refusals are **400 with a `code`** (`CardBindingException`):
  `CARD_NOT_SUPPORTED` "Faqat Uzcard va Humo kartalari qabul qilinadi", `CARD_EXPIRY_INVALID`,
  `CARD_EXPIRED`, `SMS_CODE_WRONG`, `SMS_CODE_EXPIRED`, `SMS_RESEND_TOO_EARLY`,
  `SMS_RESEND_UNAVAILABLE`, `BINDING_NOT_FOUND`, `CARD_BINDING_FAILED`; unreachable → 502.
- **`AtmosPaymentProvider`** is active only under the `atmos` profile — `staging,atmos` on
  the test server (the sandbox), `prod,atmos` in production — and refuses to start unless
  `ATMOS_BASE_URL`, `ATMOS_CONSUMER_KEY`, `ATMOS_CONSUMER_SECRET` and `ATMOS_STORE_ID` are
  set. `ATMOS_PLATFORM_RECIPIENT_ID` does not stop the start-up (a sandbox is given it
  last); without it only a platform split is refused, with `RECIPIENT_MISSING`, and it is
  logged as missing at startup. Every `ATMOS_*` value is trimmed as it is bound.
  ATMOS answers only calls from an IP it has allowed, so the credentials work on the
  deployed server and nowhere else. Token from `POST /token`
  (Basic auth, `client_credentials`), cached until `expires_in` − 5 min; a 401 refreshes
  it once. `ofd_items` are sent only when `ATMOS_OFD_CODE_RENTAL` and
  `ATMOS_OFD_CODE_SERVICE` are both set; `ofd_url` is saved on the payment. It never
  logs a request or response body — only operation names, ids and result codes.
- **Who a split is paid to.** The owner's split carries the recipient id an admin entered on
  the owner's legal details (`owner_legal_details.atmos_recipient_id`, required once the
  contract is `signed`), and the platform's split carries none — the provider fills in
  `ATMOS_PLATFORM_RECIPIENT_ID`. Which **field** names it on the wire is
  `ATMOS_RECIPIENT_FIELD`: `store_id` (the default, as the request examples show) or
  `terminal_id`, in which case `store_id` stays our own merchant store. It is a setting
  because the ATMOS docs contradict themselves, so a wrong payout is a restart, not a release.
- **A recipient we do not know yet.** Under `staging` only, a payment or hold whose recipient
  has no ATMOS id is collapsed into **one payment of the same total to `ATMOS_STORE_ID`**,
  with a warning naming it — that is how the test server walks a real sandbox payment end to
  end before any owner's contract is signed. Nobody is paid out by it. Under `prod` it is
  refused (`RECIPIENT_MISSING`) and no combination of profiles turns the fallback on there:
  money that cannot reach the person it is owed to must not be taken.
- **One recipient is a single payment, several are a multi-transaction.** The ATMOS docs
  describe the two as separate flows, and an ordinary booking pays one recipient (the owner;
  the platform takes nothing from a booking), so it goes through `merchant/pay/create` →
  `pre-apply` → `apply`, and only a team game's two or more splits use `merchant/bulk/...`.
  `confirmCharge` follows the same rule from the number of transaction ids, so a restart
  between creating and confirming changes nothing. The apply answer is read whichever shape
  it has: `store_transaction` is an object in a single payment and an array in a bulk one,
  and the receipt URL is top-level in the first and per transaction in the second.
- **`pay/get` believes the transaction, not the code.** The docs' own example answers
  `STPIMS-ERR-092` ("transaction closed") while carrying a confirmed transaction with its
  `success_trans_id`: taking the code at face value would report a payment that went through
  as given back, and `PaymentReconcileJob` would eventually fail a booking the player had
  paid for. "Closed" is read as a give-back only when the answer carries no transaction.
- **What we cannot read, we never call a failure.** A timeout is a `ProviderTimeoutException`
  (the payment stays `processing`), and so is an answer that is not JSON — a gateway error
  page says nothing about whether the money moved. That includes the one retry after a 401:
  it is made outside the `catch` block, because an exception thrown inside a `catch` is not
  caught by its own `try` and used to escape as a transport error.
- **`FakePaymentProvider`** is the default in dev and tests (`app.payments.provider=fake`),
  and on the staging server. It is never created under `atmos` — including `staging,atmos`,
  so the two are never wired in together — and the application refuses to start with it
  under `prod` (`FakeProviderGuardTest`). It answers like ATMOS, with ATMOS's codes, behind the
  same interface — switching to `AtmosPaymentProvider` changes nothing above it. Its state
  (bindings, cards with balances, charges and holds with their splits, reverses) is in the
  `fake_payment_*` tables (changeset 044, `JdbcFakeProviderStore`, plain JDBC — no
  entities), so it **survives a restart**; each call commits on its own (`REQUIRES_NEW`),
  so our rollback never undoes what "the provider" did. Tokens are kept only as SHA-256,
  card numbers and SMS codes not at all. ATMOS keeps 1.5% of each split (rounded so the
  recipient gets `netAfterFee`); every split must be ≥ 500 so'm; a reverse is whole, once,
  within 3 months; a hold lapses after its duration; release is idempotent; the same
  account never charges or holds twice. Settings `app.payments.fake.*`: `otp-length` (6),
  `otp-ttl-seconds` (180), `resend-cooldown-seconds` (60), `starting-balance` (10 000 000
  so'm), `min-amount` (500 so'm), `refund-window-months` (3), `delay-min-ms` /
  `delay-max-ms` (300 / 800; `@DatabaseTest` sets 0).
- **Is the provider there?** `PaymentProvider.check()` answers a `ProviderCheck`
  (`provider`, `ok`, `host`, `environment`, `errorCode`) and never throws. `AtmosPaymentProvider` asks for a
  **fresh** access token — the smallest call that proves the network, the base URL, the
  consumer key and the secret — and turns a refusal into its code (`HTTP_401`, `TOKEN`,
  `TIMEOUT`, `UNREACHABLE`); `FakePaymentProvider` is always ok with no host and no
  environment, because there is nothing to reach and it is neither. `environment` is
  `ATMOS_ENVIRONMENT` (`sandbox` / `production`) — the sandbox answers on the same host as
  production, so it is configured, not derived (see "Environment variables and profiles"). It carries the base URL's **host**, never the URL, the token or a
  credential. `ProviderCheckService` behind `GET /api/admin/payments/provider-check` is what
  the admin panel's Sozlamalar page shows (see "Admin panel API"). Nothing is charged or
  stored and nothing is audited, but every call is a real round trip — don't poll it.
- `GET /api/dev/fake-payments?limit=` (dev profile + `X-Dev-Login-Key`, no JWT; 404
  otherwise): the latest fake cards (masked, balance, scenario), charges and holds (status,
  splits, commission, what each recipient receives) and reverses. No token anywhere.

#### To simulate X in dev, do Y (fake provider)

| To simulate | Do |
|---|---|
| A card that works | any 16 digits starting `8600` (Uzcard) or `9860` (Humo), expiry `MM/YY` not passed, e.g. `8600 1234 1234 1234`, `12/29` — no Luhn check |
| A card we don't take | any other prefix, e.g. `4111…` → 400 `CARD_NOT_SUPPORTED` |
| A bad / past expiry | `13/29` → `CARD_EXPIRY_INVALID`; last month → `CARD_EXPIRED` |
| The SMS code | any 6 digits (`app.payments.fake.otp-length`) |
| A wrong code | `000000` → `SMS_CODE_WRONG` (the binding stays open) |
| An expired code | `999999`, or wait 180 s → `SMS_CODE_EXPIRED` |
| Resending the code | `POST /api/cards/resend {bindingId}`; within 60 s → `SMS_RESEND_TOO_EARLY` |
| A declined card | card ending `0002` (`STPIMS-ERR-058`): booking stays `pending`, row `failed` |
| Insufficient funds | ending `0051` (`-057`), or spend the 10 000 000 so'm balance (`-112`) |
| An expired card at payment | ending `0054` (`-067`) |
| A lost answer that did charge | ending `0091`: `PaymentSettler` asks `getCharge` inside the request and the booking comes back `paid` — the ATMOS `STPIMS-ERR-041` case. On a hold it is the **capture** that times out (and went through): a join answers 202 and the job confirms the game |
| A lost answer that did not | ending `0092`: asked at once too, and the booking comes back `declined` with the card's own code. On a hold the capture times out and the hold is gone: the job undoes the join |
| An answer that never comes | ending `0093`: `getCharge` keeps saying "pending", so the payment really does stay `processing` — the booking keeps its slot past its hold, and no second charge is allowed, until `PaymentReconcileService` gives up on it |
| The provider down | ending `0099`: every charge / hold → 502 |
| A refund the provider refuses | ending `0098`: payments work, every reverse is refused (`-15`) → `refund_failed`, then admin retry / mark-refunded |
| A team game whose capture fails | creator's card ending `0097`: the hold works, the capture is refused with an ordinary code (`STPIMS-ERR-058`) and the hold stays → the joiner is reversed, the hold released, the game ends |
| A hold that lapses | create a team game, then let `hold_expires_at` pass (or move `games.starts_at` inside 3 h and let `GameTimeoutJob` release it) |
| An amount ATMOS refuses | any split under 500 so'm → `STPIMS-ERR-039`, a `failed` row |
| What happened | `GET /api/dev/fake-payments` with `X-Dev-Login-Key` |
- `LegacyCheckoutProvider` (Payme/Click hosted checkout) is **legacy**, kept only as the
  no-`cardId` branch of the friends-game pay endpoint. Never use it in a new flow.

### Payment rows

- `amount` is the gross charged (or the cash taken); `owner_amount + platform_amount =
  amount`; `card_fee_expected` is what ATMOS is expected to take, its rate on each split
  (0 for cash; on rows from before changeset 040, what the card fee and rounding added);
  `provider_commission` is what ATMOS reports. **Amounts never change after insert**;
  only statuses move forward.
- **Never reversed twice.** Every give-back, refund, capture, return and reconciliation
  first locks the payment row and re-reads its *committed* status
  (`PaymentService.stillAsSeen` → `PaymentRepository.lockStatus`, a native
  `SELECT status … FOR UPDATE`, because a JPA `findForUpdate` of an entity already loaded
  in the transaction answers with the stale copy). If another transaction has moved it
  meanwhile — refunded it, started giving it back — nothing is sent to the provider. Two
  cancellations at once (player and owner) reverse once (`NoDoubleReverseTest`).
- Statuses: `processing` (provider called, answer not in) → `paid` / `held` / `failed`;
  `held` → `captured` or `released`; a give-back goes through `return_pending` →
  `refunded` / `released`; a capture through `capture_pending` → `captured`. A give-back
  ATMOS declines leaves the original `refund_failed` and its refund row `failed` (with
  `failure_reason`) until an admin retries it or marks it refunded by hand
  (`refunded_manually`, `manual_refund_reason`, `manual_refund_by`); both rows then end
  `refunded`. A **cash** row voided by undoing the owner's "cash received" mark becomes
  `cancelled` (changeset 050) — not a refund, no money moved through the platform.
- `provider_txn_id`: the charge's transaction ids (comma-separated, one per split) or the
  hold id. `provider_success_txn_ids`: what a reverse needs. `hold_expires_at`: when an
  unsettled hold lapses.
- A refund is a NEW row with the negative gross (fee included) and `refund_reason`.
- Payment types: `booking`, `remainder`, `team_game`, `game_share`, `service_fee`,
  `refund`; `game_team_id` ties a team game's payments to its (game, team).

### Team game money → ATMOS operations

| Rule | The creating team (hold) | The other team (charged) |
|---|---|---|
| Creating a game | **hold** for (minutes to the 3-hour point) + 120, capped at `app.payments.hold-max-minutes` (43 200); longer → the game cannot be created | — |
| Waiting, slot taken by a booking | **release** | — |
| Waiting, 3-hour point (`GameTimeoutJob`) | **release** (already released counts as done) | — |
| Waiting, leader cancels before the deadline | **release** | — |
| Waiting, leader cancels after the deadline | **capture** (money kept) | — |
| Waiting, leader cancels past the 3-hour point | **release** | — |
| Opponent joins | **capture**, last | **charge** |
| Confirmed, a leader cancels before the deadline | the leaver's money: **reverse** | the team that stays: nothing |
| Confirmed, a leader cancels after the deadline | the leaver's money: kept | the team that stays: nothing |
| Confirmed, a leader cancels past the 3-hour point | the leaver's money: kept | the team that stays: **reverse** |
| Owner cancels the booking / admin blocks the stadium | **reverse** | **reverse** |
| A team's remainder in the app | **charge**; reversed with the rest of that team's money when it is given back | |

Each row is tested in `TeamGameOperationMappingTest`. Joining (see "Joining" under
team_vs_team, tested end to end in `TeamGameJoinPaymentTest`): (1) book the slot
(EXCLUDE constraint), (2) charge the joiner, (3) capture the waiting team's hold, each
step committed. If (2) is declined or unanswered the join is dropped and the game keeps
waiting (an unanswered charge is given back by reconciliation). If (3) is refused with
any code the joiner's charge is reversed, the hold released, the booking deleted, the
game cancelled and both teams notified; if (3) has no clear answer it is left
`capture_pending` (202 `PAYMENT_PENDING`) and the reconcile job confirms or undoes the
game. A join waits while the creating team's hold is still `processing`.

Ordinary bookings and friends games use **charge / reverse**: a refund (player before
the deadline, owner at any time, admin block) reverses every split's transaction in
full. Friends games never held money — each player pays as they go and the slot is
booked once everyone has — so they pay by card (`POST /api/games/{id}/pay?cardId=`)
and are reversed if the game is called off. Cash is never touched.

### A confirmation ATMOS could not answer (`STPIMS-ERR-041`)

ATMOS answers `pay/apply` with `STPIMS-ERR-041` ("Проблема соединения") for payments it has
**in fact taken**: `pay/get` says `confirmed` moments later. It is in `AtmosCodes.UNAVAILABLE`,
so nothing treats it as a decline:

- `DeclineReason.of` gives **no reason** for any unavailable code. A connection problem is
  not the card's fault, and showing it as `CARD_DECLINED` sent the player off to try another
  card for a payment that had already gone through.
- The charge was created, so whether the money moved is unknown: the row stays `processing`
  and the card is never charged again (`refusedByProvider`, `created = true`).
- `PaymentSettler` then asks `pay/get` **straight away**, from the controller, after the
  charge's own transaction has committed — `app.payments.settle-now.attempts` times (6),
  `…delay` apart (3 s), stopping as soon as the row moves and never holding the request up
  for longer than `…budget` (20 s), because one ATMOS call alone may take
  `app.atmos.timeout-seconds`. It does this by calling
  `PaymentReconcileService.reconcile`, **the same call the job makes**: there is no second
  path from money to a confirmed booking. A provider still not answering is not an error —
  the row simply stays where it was. `attempts: 0` turns it off.
- `BookingExpiryJob` never expires a booking whose card payment is `processing` or
  `capture_pending` (`BookingRepository.findExpirablePending`), so the slot is still there
  when the answer arrives. Nothing waits for ever: the charge is failed after
  `ABANDONED_AFTER`, and the next run of the job expires the booking.
- The Mini App shows `paymentState: processing` as "Toʻlov tekshirilmoqda" and polls the
  booking until it settles.

**Which difference causes it** is not settled: the app's `pay/apply` is `deploy/atmos-smoke.sh`'s
`pay/apply` field for field (same single endpoint — an ordinary booking pays one recipient —
same numeric `transaction_id`, same `otp` `111111`, sent straight after pre-apply), and what
differs is what `pay/create` named three calls earlier: the owner's recipient in `store_id`
(our own store with `ATMOS_RECIPIENT_FIELD=terminal_id`), a 36-character UUID `account`, and
`ofd_items` when both OFD codes are set. `./atmos-smoke.sh --bisect` binds a card once and
takes the payment again per difference, reversing each; the first failing variant names the
cause. The log line `ATMOS single payment: account=… createStore=… recipientField=… receipts=…`
says which shape a given server sends.

### Reconciliation

`PaymentReconcileJob` runs every five minutes and settles every payment stuck for over
five minutes in `processing`, `return_pending` or `capture_pending`, each in its own
transaction:

- `processing` charge → `getCharge`: paid → the booking is confirmed (through
  `applySuccessfulBookingPayment`), the friends game told, the team's remainder settled
  — or the money given straight back if what it paid for has gone (an abandoned join);
  unconfirmed after 30 minutes → failed, no money moved.
- `processing` hold → `getHold`: held, or failed (the waiting game it was for ends).
- `return_pending` → reversed if the money was taken, released if it is held. A reverse
  ATMOS declines becomes a failed refund (`refund_failed` + a `failed` refund row), so
  the job stops trying; a release that fails stays `return_pending` for the next run.
- `capture_pending` → captured if still held; released with an error if it lapsed.
- Then the **team joins** still `joining` after five minutes are resumed
  (`GameService.resumeStuckJoins` → `TeamGameService.resumeJoin`): confirmed or undone
  from the rows just settled. A joining team's own charge is never given back by the
  payment pass — the join decides. `return_pending` is finished by
  `PaymentService.completeReturn`, the same code the join's undo uses.

Idempotent: settled rows are not picked up, and a reverse or release of something
already gone counts as done. The job never touches `failed` or `refund_failed` rows —
from then on only an admin retries them, so the two never race.

### Saved cards

`saved_cards` keeps the provider token **encrypted at rest** (AES-256-GCM,
`CARD_TOKEN_KEY`; required under the `prod` and `atmos` profiles), the provider's card
id, a masked number (`8600 •••• •••• 1234`, `provider/CardMask` for both providers; rows
saved earlier keep `8600 •••• 1234`), the expiry ATMOS reports and an active flag. The
card number and the expiry the player typed go straight to ATMOS and are **never stored
or logged**; the token never appears in any API response. The player sees only the
masked number and expiry; deleting a card removes it at ATMOS (`removeCard`) and sets
`is_active = false`. Endpoints: `POST /api/cards` (→ `bindingId`, `maskedPhone` like
`+998 ** *** ** 58`), `POST /api/cards/resend {bindingId}` (a new code after 60 s),
`POST /api/cards/confirm`, `GET /api/cards`, `DELETE /api/cards/{id}`. Refusals: see "The
provider" above.

**Never logged or returned**: card numbers, expiry dates, SMS codes, card tokens, ATMOS
credentials or access tokens. The request DTOs and provider records override
`toString` to mask them, and a test checks it.

### Open ATMOS questions

- [ ] `store_id` vs `terminal_id`: which identifies a split's recipient (the bulk and
      hold requests use `store_id`, the responses `terminal_id`).
- [ ] Bulk `pay/create`: `store_id` per `params` item (the table) or one at the top (the
      example)? We send both. Are `ofd_items` accepted per item?
- [ ] `amount`: a number (bulk example) or a string (hold examples)? We follow each example.
- [ ] The OTP to send when applying a token payment or hold — the docs show `111111`.
- [ ] Do hold capture/cancel/get take the multi-hold `parent_id` or each item's `hold_id`?
      We use `parent_id`.
- [ ] How is a captured hold reversed — which transaction id? We send the hold id.
- [ ] `pay/get` and `hold/{id}` status values are not listed; `confirmed: true` is read as
      success / held.
- [ ] `hold_till`'s format; and whether `DELETE /hold/{id}` answering `STPIMS-ERR-145`
      means "released" (its example) or an error.
- [ ] The token lifetime: the text says 3600 s, the example 2525.
- [ ] `/token`: `grant_type` in the form body (the docs) or the query string?
- [ ] Card binding: no way to resend the SMS code is documented (only `pay/retry-otp`, for
      payments). `AtmosPaymentProvider.resendBindingCode` answers `BIND_RESEND_UNSUPPORTED`
      (→ `SMS_RESEND_UNAVAILABLE`: enter the card again). Nor are codes for an expired OTP or
      a resend too soon (we use `OTP_EXPIRED`, `OTP_RESEND_TOO_EARLY`).
- [ ] The code for a reverse past the refund window (the fake uses `STPIMS-ERR-113`), and the
      window itself (3 months assumed).
- [ ] Is the 500 so'm minimum per split transaction or per payment? The fake checks each split.

