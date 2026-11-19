# API changes for the frontend — team level, team page and chat (2026-11-19)

Backend half of the Mini App's team-level, Teams-section and chat rework
(`../minipolyaFrontend/docs/superpowers/specs/2026-11-19-team-level-teams-chat-design.md`).
Money is tiyin and timestamps are UTC ISO-8601, as always. Every error is
`{ "success": false, "data": null, "message": "<text>" }` with the status named.

Changesets: **054-team-level**, **055-game-message-system**.

## 1. The team's level

`teams.level` is `"havaskor"` | `"orta"` | `"professional"` — lowercase on the wire, like
every other enum here. It is how seriously a team plays, and it **never bars a game**: any
team may challenge or accept any other.

A team created before this change has **no level**. The field is simply absent, which means
"not set" — not an error, not a default.

### `POST /api/teams` — `level` is now required

```json
{ "name": "Alpha Bravo", "level": "orta" }
```

- Missing or blank → **400** `"level is required"`.
- Not one of the three → **400** `"Unknown team level: elite"`.

### `PATCH /api/teams/{id}` — new

The captain changes the level. Nothing else about a team is editable.

```
PATCH /api/teams/{id}   { "level": "professional" }
→ { success: true, data: TeamDetailResponse, message: "Team updated" }
```

- Not the leader → **403** `"Only the team leader can perform this action"`.
- Unknown team → **404** `"Team not found"`.
- Same validation on `level` as create.
- Audited as `TEAM_LEVEL_CHANGED`; choosing the level the team already has writes no entry.

### Where the level is returned

| Response | Field |
|---|---|
| `TeamResponse` (`GET /api/teams/me`) | `level` |
| `TeamDetailResponse` (`GET /api/teams/{id}`, create, join, add member, patch) | `level` |
| `GameTeamResponse` (`teams[]` on a game detail) | `teamLevel` |
| `GameChallengeResponse` (`GET /api/games/challenges`) | `waitingTeamLevel` |
| `GameMessageResponse` (`GET /api/games/{id}/messages`) | `teamLevel` |

All are **absent** when the team has no level.

### `GET /api/games/challenges?level=…` — new filter

`level=havaskor|orta|professional` narrows the board to the games whose **waiting team** is
of that level, filtered in the query on the server. Leaving it out lists every level, teams
without one included. Anything else → **400** `"Unknown team level: …"`.

Combines with the existing `date`, `format`, `stadiumId`, `page`, `size`.

## 2. The team page

### `GET /api/teams/{id}` — two new fields on `TeamDetailResponse`

```json
{
  "id": "…", "name": "Alpha Bravo", "leaderId": "…", "leaderName": "Ali Valiyev",
  "members": [ … ],
  "level": "orta",
  "gamesPlayed": 4,
  "upcomingGame": {
    "gameId": "…",
    "startsAt": "2026-11-21T15:00:00Z",
    "status": "confirmed",
    "stadiumName": "Chilonzor Arena",
    "opponentName": "Delta FC"
  }
}
```

- `gamesPlayed` (integer) — confirmed team games of this team whose hour has started.
  Nothing sets a game `completed` yet, so that is the only honest count; it is never an
  estimate.
- `upcomingGame` — the **soonest** team game ahead of this team, `open` (waiting for an
  opponent) or `confirmed`. **Absent** when there is none.
  - `opponentName` is **absent while an opponent is still awaited** — show "Raqib
    kutilmoqda", never a guess.

### `DELETE /api/teams/{id}/members/{userId}` — a member may now leave

The call already existed for the captain removing a member. It now also accepts **the member
themselves** (`userId` = the caller), so a player can walk out of a team.

- Anyone removing anybody else → **403** `"Only the team leader can perform this action"`.
- The leader, whoever asks → **400** `"The leader cannot be removed; delete the team
  instead"`.
- Not a member → **404** `"User is not a member of this team"`.

## 3. The chat

Unchanged: the chat is open **exactly while the game is `confirmed`**, a leader who joined
partway through sees only what came after their join, and a team that left loses it.

### `GameMessageResponse` — new fields

```json
{
  "id": "…", "gameId": "…",
  "userId": "…", "userName": "Ali Valiyev",
  "message": "Soat 20:00 da koʻrishamiz",
  "createdAt": "2026-11-19T14:42:00Z",
  "kind": "user",
  "side": "b", "teamId": "…", "teamName": "Delta FC", "teamLevel": "professional"
}
```

| Field | Meaning |
|---|---|
| `kind` | `"user"` or `"system"` — every message sent before this change is `"user"` |
| `event` | system lines only: `"game_confirmed"` or `"team_joined"` |
| `eventTeamName` | the team a system line is about (`team_joined`) |
| `side` | `"a"` / `"b"` — the sender's side; absent for a system line or a sender in neither team |
| `teamId`, `teamName`, `teamLevel` | the sender's team in this game |

**A system line has no sender:** `userId` and `userName` are absent. Render the text from
`event` (and `eventTeamName`) in the reader's own language — the stored `message` is the
event code and is not meant to be shown.

System lines are written when a team game is **confirmed**: first `game_confirmed`, then
`team_joined` naming the team that took the free side. They are ordinary messages, so they
page and sort with the rest.

### `GET /api/games/{gameId}/messages?before=…` — new cursor

`before` is an ISO instant: the page of messages **strictly older** than it, newest first —
what "load the earlier messages" means. Without it the endpoint behaves exactly as before
(`page`, `size`, newest first). Use the oldest loaded message's `createdAt` as the next
`before`; a page number would shift under the reader when a message arrives meanwhile.

### `GameDetailResponse.chatMessageMaxLength` — new

Team games only: the longest message the server accepts (currently `1000`). Use it for the
composer's limit and its counter instead of a number copied into the client. The send
endpoint still answers **400** `"message must be at most 1000 characters"` past it.

## Not shipped

- **A Telegram push for a new chat message.** It needs a new `notifications.type` value (a
  CHECK constraint, so its own changeset), a per-user per-game throttle that survives a
  restart, and a "has this user opened the chat recently" signal the platform does not
  record at all. Logged as a follow-up rather than half-built.
- **A team logo.** The only upload path here is `StadiumPhoto`, owner-scoped, with its own
  table, controller and storage directory. The Mini App draws the team's initials on a
  colour derived from its id instead. Logged as a follow-up.
