# API changes for the frontend — the payment provider check (2026-11-25)

One new admin endpoint. Nothing existing changed, nothing was removed.

## `GET /api/admin/payments/provider-check` — new

Which card provider this server talks to, and whether it answers right now. Admin only
(`ROLE_ADMIN`, like everything under `/api/admin/**`): 401 without a token, 403 for anyone
else.

```json
{
  "success": true,
  "data": { "status": "ok", "provider": "atmos", "host": "apigw.atmos.uz" },
  "message": null
}
```

| Field | Always? | What it is |
|---|---|---|
| `status` | yes | `"ok"` or `"failed"` |
| `provider` | yes | `"atmos"` (the real card provider) or `"fake"` (the stand-in, which reaches nothing) |
| `host` | no | the host of the provider's base URL, e.g. `apigw.atmos.uz`. **Absent under `fake`** — there is nothing to reach |
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
