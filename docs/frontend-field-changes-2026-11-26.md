# API changes for the frontend (2026-11-26)

No field was added, removed or changed meaning. One **message** changed, and one error that
players will now actually see needs a sentence in the Mini App. Everything else in this
round was server-side: the ATMOS hold calls and what a refusal writes to the log
(`docs/claude/atmos-endpoint-audit.md`).

## 1. A team game can only be created 7 days ahead

`POST /api/games` with `type: "team_vs_team"` now answers **400** when the game is further
off than the hold cap (`ATMOS_HOLD_MAX_MINUTES`, 10 080 minutes = **7 days** by default;
it was 30 days and the card network would not have held the money that long anyway):

```json
{
  "success": false,
  "data": null,
  "message": "A team game can be created at most 7 days before kick-off: creating it holds your team's money on the card until the game is confirmed, and a card payment cannot hold it for longer. Create the game nearer the date."
}
```

**Why it happens.** Creating a team game holds the creating team's money on the card until
an opponent arrives. The hold has to last until three hours before kick-off, so a game a
fortnight away needs a hold of two weeks — one of them asked for 19 591 minutes — and no
card hold lasts that long. The game is therefore not created at all, rather than created
with nobody's money behind it: a team that could withdraw at the moment an opponent commits
would be worse than a team that has to come back on Monday.

**What the Mini App should do.**

- The date picker for a **team game** should not offer a kick-off more than 7 days out.
  There is no endpoint for the number — it is not configurable per server in practice — so
  hardcode 7 and we will tell you if it changes.
- If the 400 still comes back (someone with the window open past midnight, a clock skew),
  show this text rather than the English message:

  > **uz** — Jamoaviy oʻyinni oʻyin boshlanishidan koʻpi bilan 7 kun oldin yaratish mumkin.
  > Oʻyin yaratilganda jamoangiz puli kartada ushlab turiladi, karta esa pulni bundan uzoq
  > muddat ushlab turolmaydi. Oʻyin sanasiga yaqinroq qoldirib qayta urinib koʻring.
  >
  > **ru** — Командную игру можно создать не раньше чем за 7 дней до начала. При создании
  > деньги вашей команды замораживаются на карте, а дольше карта их держать не может.
  > Попробуйте ближе к дате игры.
  >
  > **en** — A team game can be created at most 7 days before kick-off. Creating it holds
  > your team's money on the card, and a card cannot hold it for longer. Try again nearer
  > the date.

This is an ordinary 400 with a `message`, not a `code`, so it needs no new mapping — but
please do not show it as "something went wrong": it is a rule, and the player can act on it.

## 2. A payment service problem is no longer shown as a declined card

Unchanged in shape, worth knowing in behaviour. When ATMOS refuses our own call rather than
the player's card — HTTP 401 or 403 from the gateway, an inactive partner, a store id ATMOS
does not know (`STPIMS-ERR-001`, `-017`, `-037`, `-044`, `-103`, …) — the API now answers

- **502** with `"To'lov tizimi vaqtincha ishlamayapti. Birozdan so'ng qayta urinib ko'ring"`,
  and nothing is kept,

where some of those cases previously came back as a **declined card** (`declineReason:
"CARD_DECLINED"`, or the 400 `code` of `CardDeclinedException`). So:

- `declineReason` and the `CARD_DECLINED` code now really do mean the **card** was refused.
  Screens that say "try another card" are right to say it only for those.
- A 502 means try again, same card. The Mini App already handles it this way; no change
  needed, but the case will come up more often than it used to and less often as a decline.

Real card problems are unchanged: `INSUFFICIENT_FUNDS`, `CARD_EXPIRED`, `CARD_DECLINED`
(blocked card, wrong SMS code, card not found).

---

# Second round (2026-11-26, later): fields and endpoints that changed

This round **does** change the API. In order of what the frontend must do:

## 3. Removed: the Payme/Click hosted checkout

- `POST /api/payments/initiate` — gone (404/405).
- `POST /webhooks/payme`, `POST /webhooks/click` — gone (401 without a JWT, 404 with one).
- `POST /api/games/{id}/pay` without `cardId` — now **400 "cardId is required"**; the
  `provider` parameter is ignored. With `cardId` nothing changed.
- `PaymentInitResponse.checkoutUrl` — gone (it was only ever on the removed checkout).

Neither app called any of these. `docs/api-reference.md` should drop them.

## 4. New `declineReason` / decline `code`: `WRONG_OTP`

`STPIMS-ERR-098` (wrong confirmation code) at payment is now its own reason, `WRONG_OTP`,
instead of `CARD_DECLINED`. It appears wherever the other three do: `declineReason` on
`BookingResponse`, `PaymentResponse` and `AdminPaymentResponse`, and the `code` of the 400
card declines. Nothing was taken. An unknown reason was already shown as a plain decline,
so older clients are safe.

## 5. New: adding a card the player already has is **409 `CARD_ALREADY_ADDED`**

`POST /api/cards` answers

```json
{ "success": false, "code": "CARD_ALREADY_ADDED", "message": "Bu karta allaqachon qoʻshilgan", "data": null }
```

when the player already has that card (same masked number and expiry), and also when ATMOS
itself says it bound the card before (it used to come back as the 502 "service down").

## 6. New 502 message: the stadium's owner cannot be paid yet

Any card payment (booking, remainder, team game create / join / remainder, friends game) can
answer **502 "Maydon egasi toʻlov qabul qilishga hali ulanmagan"** — production only, when
the stadium's owner has no signed ATMOS contract. Like the other 502: not the card's fault,
nothing was taken; the player cannot fix it by trying again soon.

## 7. Admin payments: a new status, three fields, a filter and an endpoint

- **Status `needs_review`** on a payment: a hold whose capture or give-back had no clear
  answer from ATMOS. `failureReason` says which ("Capture unclear: …" / "Give-back
  unclear: …"). Only an admin settles it.
- **`POST /api/admin/payments/{id}/resolve-review`** `{ "outcome": "captured" | "released" |
  "refunded", "reason": "10–500 chars" }` → the payment detail, `"Payment resolved"`.
  400 `"outcome must be one of captured, released, refunded"`, `"reason must be at least 10
  characters"`, `"Only a payment that needs review can be resolved"`.
- **`GET /api/admin/payments?status=`** takes several comma-separated statuses. The
  "Ushlab turilgan" tab should send `held,capture_pending,return_pending,needs_review`.
- New fields on `AdminPaymentResponse` (list and detail, absent when null): `holdExpiresAt`
  (UTC, holds only), `gameTeamId` and `teamSide` (`"a"` / `"b"`, team-game payments only).
- New audit actions: `PAYMENT_NEEDS_REVIEW` (system), `PAYMENT_REVIEW_RESOLVED` (admin).
