# API changes for the frontend (2026-11-25)

One new admin endpoint, one new audit field, and two behaviours worth knowing about a
payment that is still being checked. No field was removed and none changed meaning.

## `GET /api/admin/payments/provider-check` — new

Which card provider this server talks to, and whether it answers right now. Admin only
(`ROLE_ADMIN`, like everything under `/api/admin/**`): 401 without a token, 403 for anyone
else.

```json
{
  "success": true,
  "data": {
    "status": "ok",
    "provider": "atmos",
    "host": "apigw.atmos.uz",
    "environment": "sandbox"
  },
  "message": null
}
```

| Field | Always? | What it is |
|---|---|---|
| `status` | yes | `"ok"` or `"failed"` |
| `provider` | yes | `"atmos"` (the real card provider) or `"fake"` (the stand-in, which reaches nothing) |
| `host` | no | the host of the provider's base URL, e.g. `apigw.atmos.uz`. **Absent under `fake`** — there is nothing to reach |
| `environment` | no | `"sandbox"` or `"production"`, from `ATMOS_ENVIRONMENT`. **Absent under `fake`.** The sandbox answers on the **same host** as production, so this is the only thing that says which side a server is on — don't try to tell them apart by the host |
| `errorCode` | no | the provider's own code for the failure. **Absent when `status` is `"ok"`** |

- Under the real provider it asks ATMOS for a **fresh access token**, which is the smallest
  call that proves the network, the base URL, the consumer key and the secret at once.
  Nothing is charged, nothing is stored, and there is no audit entry (a check is not an
  action on data).
- **A provider that is down is still 200.** `{ "status": "failed", "errorCode": "HTTP_401" }`
  is the answer, not an error — so don't treat it as a failed request. Codes seen so far:
  `HTTP_401` (the credentials are wrong), another `HTTP_<status>`, `TOKEN` (an answer with no
  token in it), `TIMEOUT` (no answer in time), `UNREACHABLE` (the call could not be made).
  Show the code as it comes; the list will grow.
- `provider: "fake"` with `status: "ok"` means **no real provider is wired in here** (a
  developer's machine, or the staging server on plain `staging`). It is not a claim that
  anything answered.
- **No token and no credential is ever in the body**, by construction: the response has only
  the four fields above, and the host rather than the URL (a URL can carry `user:password@`).
- Calling it costs an ATMOS round trip, so don't poll it.

## A card payment that is still being checked — no new field, new behaviour

ATMOS answers `pay/apply` with `STPIMS-ERR-041` ("Проблема соединения") for payments it has
**in fact taken**, so a booking could come back with `paymentState: "processing"` even though
the money had left the card. Two things changed around that; the fields are the ones that
were already there.

- **`paymentState: "processing"` now resolves in seconds, not minutes.** The backend asks the
  provider again before answering `POST /api/bookings`, `POST /api/bookings/{id}/pay` and
  `POST /api/bookings/{id}/pay-remainder` (up to six asks, three seconds apart), so those
  responses usually already say `paid` or `declined`. When they still say `processing`, the
  outcome is genuinely unknown.
- **Never show `processing` as unpaid.** Show "Toʻlov tekshirilmoqda" (Не оплачено → *Проверяем
  оплату*) and read the booking again every few seconds until `paymentState` changes. The Mini
  App does this in `useBooking` / `PaymentSummary`.
- **A booking whose payment is `processing` is not offered a new payment** and does not expire
  while it lasts: its `expiresAt` may be in the past while `status` is still `pending`. Treat
  "the hold ran out" as a reason to hide the pay button, not as a reason to say the booking is
  gone — `status` is what says that.
- **`declineReason` is absent when the provider could not be reached.** A connection problem is
  not the card's fault, so such a payment is `paymentState: "failed"` with no reason, not
  `"declined"` with `CARD_DECLINED`. Keep the "try again" wording for `failed`, and card-specific
  wording ("your card was refused") only for `declined`.

## Audit log: `BOOKING_CREATED` now carries `cardPrice`

`GET /api/admin/audit` entries for `BOOKING_CREATED` have one more change line:

| Field | What it is |
|---|---|
| `price` | the **owner's** price for the hour, in tiyin — unchanged, same meaning as before |
| `cardPrice` | what the player's card was charged for the booking, in tiyin (the booking's own `cardPrice`) |

`price` alone was being read as "what was paid", which on a card booking it never was. Add a
label for `cardPrice` beside "Narx" — "Karta narxi" / "Цена по карте" — and format it as money
like the rest. `MANUAL_BOOKING_CREATED` still has only `price`: a walk-in is cash, and cash
carries no card fee. Older entries have no `cardPrice`; render them as before.
