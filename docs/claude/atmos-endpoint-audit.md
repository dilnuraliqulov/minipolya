# ATMOS endpoint audit

> Part of the MiniPolya CLAUDE.md doc set. Companion to [payments.md](payments.md), which
> stays the place the payment flows are described; this file is the line-by-line comparison
> of **every call `AtmosPaymentProvider` makes** against <https://docs.atmos.uz/en/>, what
> was wrong, what was changed, and what only ATMOS can answer.
>
> Audited: 26 Nov 2026, against the English docs page as served that day. Branch
> `feature/atmos-sandbox`.

## Why this exists

`POST /hold/multiple/create` answered **HTTP 403 every time** on the sandbox, while
`merchant/pay/*` worked with the same access token from the same server. ATMOS say holds
work in the sandbox. So either our hold calls are wrong, or that endpoint is not open to our
merchant account — and the log could not tell us which, because it said only:

```
ATMOS answered HTTP 403 to hold/multiple/create
```

An HTTP 403 never reaches ATMOS's business logic, so it carries **no result code**: the
reason is in the response **body**, which we were throwing away. That is now fixed (see
[Logging](#logging)), and `deploy/atmos-smoke.sh --hold` reproduces the call from the server
in one command. **Until that body has been read once, the 403 is not diagnosed** — see
[The 403: where it stands](#the-403-where-it-stands).

Everything else below is the audit we did while we were in there, and it found real bugs
that had nothing to do with the 403.

## What was wrong, in one list

| # | Where | What | Severity |
|---|---|---|---|
| 1 | `releaseHold` | The docs' own sample answer to `DELETE /hold/{id}` is `result.code = STPIMS-ERR-145` ("холдирование отменено"). Read like every other envelope, **every release that worked threw `HoldCancelledException`** and never returned a `HoldOutcome`. Both callers in `PaymentService` happen to catch that exception as "already let go", so the payment still ended `RELEASED` — the outcome was right by luck, and the next caller would not have been so lucky | correctness |
| 2 | `hold/multiple/create` | `store_id` was sent as a **number** when it was all digits, so a store id of `"0001"` left as `1` | bug |
| 3 | `confirmHold` | The per-item `result` in the answer was ignored: one item declining under an OK envelope read as a whole hold held | bug |
| 4 | `confirmHold` | `hold_till` was taken from the **last** item, not the earliest — the hold was believed to last longer than it does | bug |
| 5 | everywhere | A non-2xx or non-OK answer logged its status or code and nothing else: no method, no path, no description, no body | diagnostics |
| 6 | `DeclineReason` | HTTP 401/403 and ATMOS's own system codes (`-001`, `-017`, `-037`, `-044`, `-103`, …) mapped to **`CARD_DECLINED`** — a player was told their card was refused because our integration was wrong | bug |
| 7 | `getHold` | `STPIMS-ERR-142` ("холдирование не подтверждено") was an exception rather than "created, not yet applied" | bug |
| 8 | hold duration | Capped at 43 200 minutes (30 days) in `application.yaml`, with no environment variable. A game two weeks out asked for a **19 591-minute** hold and got it | risk |

Nothing was found wrong with `token`, the card-binding calls, `pay/*` or `bulk/pay/*`
beyond the open questions that were already recorded — which matches what the sandbox shows:
those calls work.

## Endpoint by endpoint

Amounts are tiyin throughout. "Ours" is `AtmosPaymentProvider` unless another class is named.
Base URL is `ATMOS_BASE_URL` = `https://apigw.atmos.uz`; **holds have no `/merchant` prefix**,
payments have `/merchant`, cards have `/partner`.

### Authorisation

| | Docs | Ours before | Ours now | Test |
|---|---|---|---|---|
| Token | `POST /token?grant_type=client_credentials`, `Authorization: Basic base64(key:secret)`, form body `grant_type=client_credentials` | same (query **and** form, as the docs' own call has both) | unchanged | `theTokenIsCachedUntilFiveMinutesBeforeItExpires` |
| Expiry | text says 3 600 s, the sample says `expires_in: 2525` | `expires_in` − 5 min, 3 600 s if absent | unchanged | same test |
| 401 | not described | refresh once, retry once | unchanged, and the retry now logs the method and path | `a401RefreshesTheTokenOnceAndRetries` |
| Business calls | `Authorization: Bearer <token>`, `Content-Type: application/json` | same | same | every test |

Docs contradiction: two examples (`bind-card/confirm`, `pay/apply`) show
`Authorization: Bearer Bearer <access-token>`. We send one `Bearer`, which works.

### Card binding — `/partner/...`

| Call | Docs | Ours before | Ours now | Test |
|---|---|---|---|---|
| `POST /partner/bind-card/init` | `{card_number, expiry}`, expiry `YYmm` | same (`MM/YY` → `YYmm`) | unchanged | `expiriesAndMasksAreConverted` |
| `POST /partner/bind-card/confirm` | `{transaction_id, otp}`; answer nests the card under `data` | same; reads `data` **and** flat | unchanged | `aConfirmedBindingGivesTheTokenMaskedNumberExpiryAndHolder` |
| `POST /partner/remove-card` | `{id, token}` | same | unchanged | `theTokenIsCachedUntilFiveMinutesBeforeItExpires` |
| `PUT /partner/bind-card/dial` | commented out in the docs' own source | not used | not used | — |
| `POST /partner/list-cards` | `{page, page_size}` → `cardDataSmallList` | not used (we keep our own cards) | not used | — |
| resend | **nothing**: `merchant/pay/retry-otp` is for a *transaction* | refused with `BIND_RESEND_UNSUPPORTED` | unchanged | `aBindingCodeCannotBeResentAtAtmos` |

### Payments — `/merchant/...`

| Call | Docs | Ours before | Ours now | Test |
|---|---|---|---|---|
| `POST /merchant/pay/create` | `{amount (number, tiyin), account, store_id, terminal_id?, lang?, ofd_items?}` → `transaction_id` | same; `store_id` as a number when numeric, the recipient per `ATMOS_RECIPIENT_FIELD` | unchanged — it works, and changing it is not this audit's business ([open question 2](#open-questions-for-atmos)) | `oneRecipientIsPaidThroughTheSinglePaymentEndpoints` |
| `POST /merchant/pay/pre-apply` | `{store_id, transaction_id, card_token}` **or** `{… card_number, expiry}` | same, never both | unchanged | same test |
| `POST /merchant/pay/apply` | `{store_id, transaction_id, otp}`; `111111` for a card token | same | unchanged | same test |
| `POST /merchant/pay/get` | `{store_id, transaction_id}` | same; a closed transaction carrying a confirmed one is read as paid | unchanged | `aClosedButConfirmedTransactionIsReadAsPaid` |
| `POST /merchant/pay/reverse` | `{transaction_id (= success_trans_id), hold_amount?, reason?}` | same, **never** `hold_amount` | unchanged | `reverseGivesTheWholeTransactionBack` |
| `POST /merchant/bulk/pay/create` | `{store_id, params[{account, amount, details}]}`; the table also lists `store_id`/`terminal_id` per item | one `store_id` per item (recipients differ per split) | unchanged ([open question 1](#open-questions-for-atmos)) | `twoRecipientsStillGoThroughTheBulkEndpoints` |
| `POST /merchant/bulk/pay/pre-apply` | `{store_id, transaction_id[], card_token}` | same | unchanged | same test |
| `POST /merchant/bulk/pay/apply` | `{store_id, transaction_id[], otp}` | same | unchanged | same test |
| `pay/retry-otp` | heading and curl say `/merchant/pay/retry-otp`, the endpoint line says `/merchant/pay/otp-resend` | not used | not used ([open question 6](#open-questions-for-atmos)) | — |
| `pay/create-reverse-partial`, `confirm-reverse-partial`, `get-reverse-partial` | partial reversal, in three calls | not used | not used, deliberately: **there is no partial refund anywhere in this application** (`CLAUDE.md`) | `BookingCancellationRefundTest` |

### Holds — no `/merchant` prefix

| Call | Docs | Ours before | Ours now | Test |
|---|---|---|---|---|
| `POST /hold/create` | `{card_token \| card_number+card_expiry, store_id, account, payment_details, amount, duration}`, all quoted → `hold_id` | **not called** | still not called by the app, on purpose (see below); `deploy/atmos-smoke.sh --hold` exercises it | smoke script a), b), d) |
| `PUT /hold/apply/{id}` | `{otp}`, id in the **path** → `hold_id`, `hold_till` | **not called** | as above | smoke script a), b), d) |
| `POST /hold/multiple/create` | `{card_token, duration, items[{store_id, account, payment_details, amount}]}`, all quoted → `parent_id`, `items[{result, hold_id, account}]` | right path, right method, right fields — but `store_id` as a **number** | `store_id` quoted; `payment_details` from the shared `detailsOf` | `aHoldIsCreatedWithItsItemsAndAppliedWithTheTokenOtp`, `aStoreIdThatStartsWithAZeroKeepsIt` |
| `PUT /hold/multiple/apply/{id}` | `{otp}` (`111111` with a card token), id in the path → `parent_id`, `items[{result, hold_id, hold_till}]` | right path and method; per-item `result` **ignored**; `hold_till` from the **last** item | an item's non-OK code decides the hold; `hold_till` is the **earliest** | `anItemThatDeclinesFailsTheWholeHold`, `anItemRefusedForAnyOtherReasonIsARefusal`, `theHoldLastsUntilTheEarliestItemLetsGo` |
| `POST /hold/{id}` (capture) | `{amount?, ofd_items?}`; `amount` must not exceed the held one | no body fields — the full amount | unchanged (no partial capture in this application); `ofd_items` still not sent ([open question 5](#open-questions-for-atmos)) | `aCaptureOnACancelledHoldIsHoldCancelled`, smoke script a), c), d) |
| `DELETE /hold/{id}` (release) | `{send_cancel_sms}`; the **sample answer is `STPIMS-ERR-145`** | `send_cancel_sms: true`, and `-145` threw `HoldCancelledException` — i.e. a successful release threw | `OK`, `-145` and `-144` are all `RELEASED`; only `-143` and anything else are a refusal | `aReleaseIsADoneReleaseEvenWhenItAnswersHoldCancelled`, `aReleaseOfAHoldThatHadAlreadyExpiredIsDoneToo`, `aReleaseThatDidNotHappenIsARefusal` |
| `GET /hold/{id}` | no body; → `store_transaction` | right path and method; `-142` was an exception | `-142` is `PENDING` (created, never applied); `-144`/`-145` `RELEASED` | `aHoldReadBackIsHeldWhenItIsConfirmed`, `aHoldThatWasNeverAppliedReadsAsPending` |

**Why the app still uses the multiple endpoint for a single split.** `confirmHold(holdId, otp)`
is given only the id, and a restart between creating and applying a hold must not change which
endpoint confirms it (that is exactly why `confirmCharge` derives single-vs-bulk from the
*number* of transaction ids it was handed). A single hold would need the create/apply pair to
be remembered on the payment row, which is a schema change for a code path we have never been
able to run. So: one shape, create → apply → capture → release, all through `/hold/multiple/*`
with the parent id, and the smoke script exercises `/hold/create` beside it so the sandbox can
say whether the two behave differently. **If the 403 turns out to be specific to
`/hold/multiple/*`, wiring the single endpoint is the fix** and that changes.

### Not audited because we do not call them

`/ppa/*` (withdrawal by PINFL/TIN), the IPS endpoints, the checkout widget and the hosted
payment page, and the Callback API. We take no callbacks from ATMOS: every outcome is read
back with `pay/get` or `getHold` (see payments.md, "A confirmation ATMOS could not answer").

## Logging

Every non-2xx **and** every non-OK result code now logs the whole picture, in one line:

```
ATMOS refused hold/multiple/create: POST /hold/multiple/create -> HTTP 403 body={"fault":{"code":900908,…}}
ATMOS answered hold/multiple/create: POST /hold/multiple/create -> HTTP 200 code=STPIMS-ERR-037 description=Store ID not found body={…}
```

- `AtmosPaymentProvider.answered` logs a non-OK result code; `httpError` logs a non-2xx status.
- Codes that are an answer rather than a problem — `-092`, `-100`, `-142`, `-144`, `-145` — and
  a declined card log the same line at **INFO**, so a real refusal is still findable at WARN.
- `AtmosSafeBody` redacts the body **by field name before anything is written**:
  `card_token`, `card_number`, `card_pan`, `pan`, `expiry`, `card_expiry`, `otp`, `token`,
  `access_token`, `refresh_token`, `card_id` (a capture answers the card token under that
  name), `card_holder`, `password`, `consumer_key`, `consumer_secret`, and `phone`. The body
  is put on one line and cut at 800 characters, because a gateway error page is HTML by the
  kilobyte. `AtmosSafeBodyTest` is the test that the hard rule still holds.

## Error mapping

`AtmosCodes` now has two sets behind `isUnavailable`, and a second question,
`isMisconfigured`:

- **`UNREACHABLE`** — the service or the bank behind it did not answer: HTTP 500/502/503/504,
  `-024`, `-026`, `-041`, `-093`, `-094`, `-102`, `-120`.
- **`MISCONFIGURED`** — our own integration, credentials or store ids: **HTTP 401, HTTP 403**,
  `-001`, `-003`, `-006`, `-007`, `-009`, `-017`, `-037`, `-044`, `-056`, `-103`.

Both mean the same thing to a player: **502 and "To'lov tizimi vaqtincha ishlamayapti.
Birozdan so'ng qayta urinib ko'ring"**, the row rolled back, and `DeclineReason.of` returning
`null` — never `CARD_DECLINED`. A misconfiguration is logged at **ERROR** as well, because it
is ours to fix and will not go away on its own.

> The brief for this audit named the message as "Toʻlov xizmatida xatolik, keyinroq urinib
> koʻring". The existing string above already is this application's one service-error message
> (`PaymentService.PROVIDER_UNAVAILABLE`, `SavedCardService.PROVIDER_UNAVAILABLE`), it is
> already what the Mini App shows for a 502, and it says the same thing — so the **mapping**
> was fixed and the wording left alone. Changing the wording is a one-line change in both
> constants if you want the other phrasing.

Deliberately **not** treated as "nothing happened": `STPIMS-ERR-118` ("статус платежа
неизвестен"). An unknown status is not an absence of one — the money may have moved — and
`isUnavailable` is also what permits a payment to be rolled back and retried.

Real card problems still reach the player as card problems: `-057`/`-112` insufficient funds,
`-067` expired, `-058`/`-079`/`-088` blocked, `-098` wrong OTP, and the rest of
`DeclineReason.KNOWN_DECLINES`.

One deliberate deviation: `-039` ("неверная сумма для платежа", e.g. under the 500 so'm
minimum) and `-042` stay card declines rather than service faults. They are not card faults,
but they are a refusal of *this* payment that no retry will fix, and payments.md already
documents them that way. Listed here so the choice is visible, not hidden.

## The 403: where it stands

What we know:

- `merchant/pay/*` works from this server with the same token. So the credentials, the IP
  allowance and the token are fine.
- Our hold calls match the docs field for field — now verifiably, in tests and in the smoke
  script's printed request shapes.
- The docs' own HTTP table says only "403 — Forbidden — Access forbidden", and nowhere do
  they mention holds needing to be enabled for a merchant account.
- `apigw.atmos.uz` behaves like an API gateway in front of ATMOS (the token call is
  `oauth2`-shaped, the answer carries a `scope`). A gateway of that kind answers **403** both
  for "this path is not a resource of the API you are subscribed to" and for "you are not
  subscribed to this API" — and it says which in the body, under a `fault` code.

What to do, in order:

1. On the server: `./deploy/atmos-smoke.sh --hold`. It prints the method, path, HTTP status
   and ATMOS code of every hold step.
2. `docker compose logs backend | grep 'ATMOS refused'` (from `deploy/`) for the body of the 403 — the gateway
   fault code is the answer.
3. If `/hold/create` works and only `/hold/multiple/create` is forbidden: the endpoint is the
   problem, not us. Ask ATMOS to open it, and wire the single endpoint meanwhile (see above).
4. If both are forbidden: holds are not on this merchant account's subscription. Send ATMOS
   the fault body and [open question 7](#open-questions-for-atmos).

Nothing in this change makes the 403 go away. It makes the next run say why.

## Open questions for ATMOS

Send these as they are; each one names what we do meanwhile.

1. **Bulk payment recipients.** `bulk/pay/create`'s example has one `store_id` at the top and
   items with no recipient; the parameter table lists `store_id` and `terminal_id` as item
   fields. Which field identifies the account the money lands in? We send one `store_id` per
   item, switchable with `ATMOS_RECIPIENT_FIELD`.
2. **`store_id` as a number or a string?** Every request example quotes it; every response
   has it as a number. Payments send a number today (and work); holds send a string, because
   a store id written `0001` must survive. Which is right?
3. **A multi-hold's follow-up ids.** Do `POST /hold/{id}`, `DELETE /hold/{id}` and
   `GET /hold/{id}` take the multiple hold's `parent_id` or each item's `hold_id`? The docs
   say only "through the standard endpoints". We send the parent id; the smoke script tries
   the item ids first.
4. **Giving back a captured hold.** Which id reverses it — the capture's `success_trans_id`
   through `merchant/pay/reverse`? Nothing in the holding section mentions a reverse.
5. **A hold's fiscal receipt.** `ofd_items` is a parameter of the capture, not of the create.
   For a *multiple* hold captured item by item, is one `ofd_items` per capture expected? We
   send none, so team-game holds currently produce no receipt.
6. **`retry-otp` or `otp-resend`?** The heading and the curl example say
   `/merchant/pay/retry-otp`; the endpoint line says `/merchant/pay/otp-resend`.
7. **The 403 on `POST /hold/multiple/create`.** With a token that `merchant/pay/create`
   accepts, from an allowed IP, with the body exactly as the docs' example. Is the holding API
   part of our sandbox subscription? *(Attach the fault body from step 2 above.)*
8. **How long may a hold last?** The parameter table says only "hold duration in minutes". Is
   there a maximum, and does it differ between Uzcard and Humo? We cap it at
   `ATMOS_HOLD_MAX_MINUTES` = 10 080 (7 days).
9. **`hold_till`'s format.** `2022-06-30T14:55:19.597`, with no zone. UTC or Tashkent time?
10. **Is `STPIMS-ERR-145` really the success answer to `DELETE /hold/{id}`?** The sample
    answer in the docs is that code. We now read `OK`, `-145` and `-144` as "released" and
    only `-143` as "not released".
11. **The OTP `111111` in production.** The docs give it for applying a payment or hold made
    with a card token. Is it the same in production, or sandbox only?
12. **`pay/get` and `GET /hold/{id}` statuses.** No status values are listed. We read
    `confirmed: true` as paid/held. Is there a status field we should read instead?

Questions 1, 2, 3, 4, 5, 9, 11 and 12 were already open before this audit; 6, 7, 8 and 10 are new.

## Commands to run on the server

```bash
cd ~/minipolya/minipolyaBackend/deploy

# the holds, start to finish; a test card from the ATMOS docs, OTP 111111
./atmos-smoke.sh --hold

# the payment and the holds in one run, on one card binding
./atmos-smoke.sh --all

# the body of any refusal, which is what the 403 question needs (still in deploy/: the
# compose file lives there, so every docker compose command runs from it)
docker compose logs --since 30m backend | grep -E 'ATMOS (refused|answered)'

# what this server sends, and the hold cap it is running with
docker compose exec -T backend printenv | grep -E 'ATMOS_(ENVIRONMENT|STORE_ID|RECIPIENT_FIELD|HOLD_MAX_MINUTES)'

# the whole test suite, from a checkout of this branch (the repository root, not deploy/)
cd ~/minipolya/minipolyaBackend && ./mvnw test
```

Nothing here needs `.env`: the smoke script runs the calls inside the backend container,
where the credentials already are.
