# Owner API

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching anything under `/api/owner/**`, including the dashboard and stadium photos.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Owner API

Owner endpoints live under `/api/owner/**` (owner role; see "Endpoint Security" for the
exceptions: adding and listing stadiums, and their photos). Every per-stadium endpoint checks ownership the same way, in any stadium status:
**404 "Stadium not found"**, then **403 "You do not own this stadium"**
(`StadiumService.requireOwnedStadium`). Prices are the owner's own, with the player price
alongside (`playerPrice`, `playerMinBookingAmount`).

| Endpoint | What |
|---|---|
| `POST /api/owner/stadiums` · `GET /api/owner/stadiums` | add a stadium (starts `pending`) · the caller's stadiums with `status` / `statusReason` — any signed-in user (R42) |
| `GET /api/owner/stadiums/{id}` | one of the caller's stadiums, any status: the `StadiumDetailResponse` that `PUT` returns, built by the same code (R35) |
| `PUT /api/owner/stadiums/{id}` | partial update (only non-null fields, each checked as on create — see "Stadiums"); `hours` replaces the whole weekly schedule, `openTime` + `closeTime` set the same hours every day, neither leaves it alone; audited `STADIUM_UPDATED` (the week as one `hours` field). No booking terms or photo URLs: `minBookingAmount`, `cancellationHours`, `clearMinBookingAmount`, `photos` are ignored if sent (the admin's booking-terms endpoint; the photo upload) |
| `POST /api/owner/stadiums/{id}/photos` | multipart field `file` → `PhotoResponse {id,url,position}`, "Photo uploaded" (R36, see "Stadium photos") — any signed-in user (D6) |
| `DELETE /api/owner/stadiums/{id}/photos/{photoId}` | `data: null`, "Photo deleted"; the rest renumbered 0..n-1; 404 "Photo not found" if not this stadium's — any signed-in user (D6) |
| `GET /api/owner/legal-details` | the caller's own `{ legalType?, atmosContractStatus, ownerContractStatus }` (R39), read-only; see "Owner legal details" |
| `GET /api/owner/dashboard/stadiums/{id}/revenue?days=` | `{ stadiumId, dailyRevenue: [{ date, revenue, bookingCount }] }`: `days` 7 or 30 (default 30, anything else 400 "days must be 7 or 30"); **every** Tashkent day of the window, oldest first, ending today, `0` / `0` on a day without payments (R43). Revenue as before: completed `booking` payments, by `created_at` |
| `GET /api/owner/dashboard/stadiums/{id}/bookings?unpaid=true` | (R38) only **live** bookings (`pending`, `confirmed`, `completed` — not cancelled or expired) with `remainingAmount > 0`, soonest `startsAt` first, paged, owner-view `BookingResponse`; `from`/`to` still apply. Filtered in the query (`BookingSpecifications.ownerUnpaid`, remaining computed in SQL as `Booking.getRemainingAmount` does) |
| `GET /api/owner/dashboard/stadiums/{id}/stats` | the counts and revenue as before, plus (R38, tiyin) `todayPaidInApp`, `todayPaidInCash`, `todayOutstanding`, `weekPaidInApp`, `weekPaidInCash`, `weekOutstanding`, `monthPaidInApp`, `monthPaidInCash`, `monthOutstanding`. PaidInApp / PaidInCash: payments of the stadium's bookings and of its team games' teams (`game_team_id`) whose money arrived (`paid`, `captured`, `completed`), by method `card` / `cash`, counted by the payment's `created_at` in the Tashkent period — one grouped query for all three periods. Outstanding: the sum of `remainingAmount` (`BookingResponseFactory.remainingAmount`, what the responses show) of live bookings **starting** in the period — one query. Periods: today, this Monday–Sunday week, this calendar month |
| `POST /api/owner/bookings/{id}/cash` | the rest of a booking taken in cash (R37), mark `cash_received` (E5) — see "Paying for a booking → Owner marks" |
| `POST /api/owner/bookings/{id}/not-paid` | mark the rest as not paid (E5): no charge, no penalty; "Marked as not paid" |
| `DELETE /api/owner/bookings/{id}/remainder-mark` | undo the mark until the end of its Tashkent day (E6), else 409 "Only an admin can change this mark"; "Mark undone" |
| `PUT /api/owner/stadiums/{id}/photos/order` | `{ photoIds: UUID[] }` = every photo exactly once, else 400 "photoIds must list every photo of the stadium exactly once" → `PhotoResponse[]` in order, "Photos reordered" — any signed-in user (D6) |

## Stadium photos

`StadiumPhotoService` / `StadiumPhotoStorage` (R36). Any stadium status. **Any signed-in
user** may call the three photo endpoints (D6: a player's add-stadium wizard uploads the
photos of their still-pending stadium before they have the owner role); ownership is
still checked, 404 / 403, as on every owner endpoint.

- **Upload:** JPEG, PNG or WebP, told by the **magic bytes** (`util/ImageType`), never the
  client's content type or file name → otherwise 400 "Unsupported image type". Over 5 MB
  → **413 "File too large"** (the service checks it, and Spring's multipart limit —
  `spring.servlet.multipart.max-file-size: 5MB`, `max-request-size: 6MB` — maps
  `MaxUploadSizeExceededException` to the same 413). At most 10 photos → 400 "A stadium
  can have at most 10 photos". A missing `file` part is 400 "file is required"; a
  non-multipart body 415.
- **Every photo is re-encoded** (`util/ImageReencoder`, D5): the size is read from the
  header first — over 40 megapixels is 400 **"Image is too large"** before anything is
  decoded (a decompression bomb; very large ones are read subsampled); then decoded (WebP
  through `com.twelvemonkeys.imageio:imageio-webp`) — anything that does not decode
  (truncated, garbage after the magic bytes, a CMYK JPEG) is 400 "Unsupported image type";
  transparency flattened onto white; scaled so the long side is **≤ 2000 px** (bilinear
  halving, then one bicubic step); written as **JPEG quality 0.85 with no metadata**, so
  EXIF (GPS, camera) never reaches the disk. EXIF orientation is not applied — the Mini
  App bakes it in before uploading. Tests: `ImageReencoderTest` (the WebP samples are
  checked-in 1×1 files in `src/test/resources/images`, `TestImages` makes the rest).
- Stored on the local filesystem under `app.uploads.dir` (`UPLOADS_DIR`, default
  `./uploads`, git-ignored) as `stadiums/<stadiumId>/<random uuid>.jpg` (photos stored
  before the re-encoding may still be `.png` / `.webp`, and are still served); the
  stored `url` is `/api/uploads/stadiums/<stadiumId>/<file>`. A new photo goes last
  (`max(position) + 1`); the lowest position is the cover (`coverPhotoUrl`). The stadium
  row is locked for each change, so concurrent uploads cannot pass the limit.
- Files follow the database: a written file is removed if the transaction rolls back; a
  deleted photo's file goes after the deletion commits. URLs that are not ours (photos
  sent as URLs on create/update before that was removed) are never touched on disk.
- **Serving:** `GET /api/uploads/stadiums/{stadiumId}/{file}` is public (`permitAll` on
  `GET /api/uploads/**`), with the image's Content-Type, `Cache-Control: max-age=31536000,
  public, immutable` and `nosniff`. Only a `<uuid>.<jpg|png|webp>` name under a stadium id
  resolves; anything else (directories, other names, `..`) is 404/400 — no listing, no
  traversal (`StadiumPhotoTest`).
- Audited as `STADIUM_UPDATED` with the `photos` URL list before → after.

