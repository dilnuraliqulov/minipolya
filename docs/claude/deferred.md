# Deferred until the payment model is final

> Part of the MiniPolya CLAUDE.md doc set. **Read this before trusting owner revenue figures or casual-game share rounding.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Deferred until the payment model is final

These were found during endpoint testing and left untouched on purpose — each one
depends on how payments, payouts and the platform's cut are finally modelled.

- **Owner revenue is defined two different ways.** `GET /api/owner/dashboard/stadiums/{id}/stats`
  sums only payments with `payment_type = booking`, so a confirmed game contributes 0 even though
  it produced a `source = game` booking on the pitch. `GET /api/owner/dashboard/stadiums/{id}/schedule`
  sums booking prices instead and therefore does include it. The same stadium reports two different
  revenue figures; the definition has to be decided before either is trusted.
- **Casual game share rounding loses so'm.** Each participant pays `total_price / max_players` with
  integer division, so the shares do not add up to the pitch price: 3 players on 130 000 collect
  129 999. `team_vs_team` no longer has this exposure — `TeamGameSplit` gives the odd tiyin to the
  second team, so its advances and remainders add up exactly.
- **The Payme/Click mocks are gone.** `POST /api/payments/initiate`, `POST /webhooks/payme`,
  `POST /webhooks/click`, `PaymeProvider`, `ClickProvider` and `LegacyCheckoutProvider` were removed:
  the webhooks were public, unsigned and read a missing status as success, so they could confirm a
  booking nobody paid for (`LegacyCheckoutRemovedTest`). Rows they left (`provider` `payme` / `click`,
  status `pending` / `completed`) stay readable; nothing writes them any more.


