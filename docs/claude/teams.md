# Teams

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching teams, members or invite links.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Teams

A team has one leader (who also holds a `team_members` row, role `leader`) and members;
`TeamService`. There is no team size limit.

### The level (changeset 054)

`teams.level` is how seriously a team plays: **`havaskor`** (friends), **`orta`** (regular)
or **`professional`** (tournament). `entity/TeamLevel`, a `CHECK` on the column, lowercase on
the wire like every other enum here; the clients translate the three words for display.

- **Required when a team is created** (`CreateTeamRequest.level`); anything that is not one
  of the three is 400 `Unknown team level: …` from `TeamLevel.fromValue`, before anything is
  written.
- **Changed afterwards only by the leader**: `PATCH /api/teams/{id}` `{ "level": "orta" }` →
  `TeamDetailResponse`, "Team updated"; anyone else 403 "Only the team leader can perform
  this action". It writes **one** `TEAM_LEVEL_CHANGED` entry (PLAYER actor, entity type
  `team`, label the team's name, `level` old → new) — and **none** when the chosen level is
  the one the team already has.
- **The column is nullable.** Every team created before the changeset has no level, which
  the apps read as "not set" and the leader is nudged to fix. Nothing else about such a team
  changes, and it may still create, join and play.
- **It never bars a game.** A team of any level may challenge or accept any other; the app
  only asks the joining captain to confirm a mismatch.
- It is returned wherever a team is shown: `level` on `TeamResponse` and
  `TeamDetailResponse`, `teamLevel` on `GameTeamResponse` and on each chat message,
  `waitingTeamLevel` on `GameChallengeResponse`. See the board's `level` filter in
  [team-games.md](team-games.md).

### What the team page reads

`GET /api/teams/{id}` carries two figures beyond the team itself, so the Mini App's team page
never counts anything of its own:

- `gamesPlayed` — confirmed team games of this team whose hour has started
  (`GameRepository.countPlayedTeamGames`). Nothing sets `games.status = 'completed'` yet, so
  "played" is "confirmed and in the past" — the only honest count the data supports.
- `upcomingGame` — the soonest team game ahead of it, waiting or confirmed
  (`TeamUpcomingGameResponse`: `gameId`, `startsAt`, `status`, `stadiumName`,
  `opponentName`). `opponentName` is **absent while an opponent is still awaited**; the card
  says so rather than inventing one.

### Members and invites

The leader adds a member by user id
(`POST /api/teams/{id}/members`, already a member → 400), or shares an **invite link**
(R32, changeset 043 `teams.invite_code`, unique, null until first asked for):

| Endpoint | Who | Answer |
|---|---|---|
| `GET /api/teams/{id}/invite` | leader | `{ code }` — created on first request: 128 random bits, base64url, 22 chars |
| `POST /api/teams/{id}/invite` | leader | `{ code }`, "Invite link renewed" — a new code; links with the old one stop working |
| `GET /api/teams/invite/{code}` | anyone signed in | `{ team: TeamResponse, isMember }`; unknown code 404 "Invite not found" |
| `POST /api/teams/join` `{ code }` (`@NotBlank`) | anyone signed in | `TeamDetailResponse`, "Joined team" — added as a `member` through the same code path as add-member; 404 "Invite not found"; already a member **409** "User is already a member of this team" |

Non-leader on the invite endpoints → 403 "Only the team leader can perform this action";
unknown team → 404 "Team not found". The code never appears in a team response. The
add-member endpoint keeps its 400 for "already a member"; only the join answers 409.

**Leaving.** `DELETE /api/teams/{id}/members/{userId}` is the leader removing a member **or
a member leaving of their own accord** — the same row disappears either way, so it is the
same call. Anyone removing anybody else → 403. The **leader** is never removed this way,
whoever asks: 400 "The leader cannot be removed; delete the team instead".

The only audited team action is `TEAM_LEVEL_CHANGED` (above); creating, renaming, inviting,
joining and leaving are not audited.

