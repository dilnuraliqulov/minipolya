# Games (casual and open) and the common game rules

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching the casual or open game flows, or any rule shared by all three.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Games (3 separate flows)

**casual — play with friends:**
- Creator picks stadium + time, invites friends via Telegram deep link
- Game is NOT public, only accessible via link
- Each player pays `total_price / max_players` + service_fee
- Slot stays FREE until ALL players have paid
- When all paid → booking created automatically
- If someone else books the slot before that → game cancelled, all refunded

**open — find players:**
- Same as casual but game is PUBLIC, visible in the app
- Any user can pay and join open spots
- System assigns sides (a/b) when game confirms


**`team_vs_team` — team vs team:** see [team-games.md](team-games.md).

**Common game rules:**
- `GameTimeoutJob` runs two cutoffs: team games whose **3-hour point** has
  passed, and every other type whose `starts_at` has passed
- When a regular booking takes the slot, find any waiting game on that
  stadium/time → cancel it `cancelled_slot_taken` and give everyone their money
  back (team games through `TeamGameService`, casual through participants)
- Game statuses: `open` → `confirmed` → `completed`
- Cancel statuses: `cancelled_slot_taken`, `cancelled_by_creator`, `cancelled_not_filled`
- `POST /api/games/{id}/pay` is the **casual** payment and refuses a team game (a team
  pays when creating or joining). With `cardId` it charges that saved card through
  ATMOS (`playerAmount(share + service fee)`; all the owner's unless
  `casual_game_service_fee > 0`, which then works like the team fee); without it, the
  legacy hosted checkout.

