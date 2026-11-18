# Teams

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching teams, members or invite links.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Teams

A team has one leader (who also holds a `team_members` row, role `leader`) and members;
`TeamService`. There is no team size limit. The leader adds a member by user id
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
Team changes are not audited (no team actions exist in `AuditAction`).

