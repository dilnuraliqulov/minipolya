# API changes for the frontend — add-stadium rework (2026-11-06)

Backend half of `docs/superpowers/specs/2026-11-06-add-stadium-rework-design.md` (D1–D15).
Everything the Mini App and the admin panel need to build against. Money is tiyin, as
always. Every error below is HTTP 400 with `{ "success": false, "data": null, "message": "<text>" }`
unless another status is given.

## 1. `POST /api/owner/stadiums` (create) — request

Who: any signed-in user (unchanged, R42). Answers `StadiumDetailResponse` (owner view),
message "Stadium submitted for review".

| Field | Before | Now |
|---|---|---|
| `name` | required, ≤ 150 | **required, trimmed, 3–150** |
| `address` | required, ≤ 300 | **required, trimmed, 5–300** |
| `district` | optional | **required**, one of the 12 (`Bektemir`, `Chilonzor`, `Mirobod`, `Mirzo Ulugʻbek` — U+02BB, `Olmazor`, `Sergeli`, `Shayxontohur`, `Uchtepa`, `Yakkasaroy`, `Yangihayot`, `Yashnobod`, `Yunusobod`) |
| `landmark` | — | **new**, optional string, trimmed, ≤ 150; blank = none |
| `latitude`, `longitude` | optional, any valid coordinate | **required**, inside Uzbekistan: lat 37.1–45.6, lon 55.9–73.2 |
| `phone` | optional, ≤ 20 | **required**, exactly `+998XXXXXXXXX` (`^\+998\d{9}$`) — no spaces |
| `description` | optional, unlimited | optional, **≤ 500** |
| `format`, `surfaceType` | required | unchanged |
| `openTime`, `closeTime` | required | required, **both on the hour** (`"08:00"`), **`closeTime` after `openTime`** (no closing after midnight) |
| `pricePerHour` | required, ≥ 0 | required, **1 000 000–500 000 000** (10 000–5 000 000 soʻm) |
| `amenities` | 8 flags | **12 flags** — see §4; any flag left out is `false` |
| `minBookingAmount` | optional | **removed** — ignored if sent (admin sets it, §3) |
| `cancellationHours` | optional | **removed** — ignored if sent (admin sets it, §3) |
| `photos` (URL list) | optional | **removed** — ignored if sent; upload with §5 after creating |

Removed fields are **ignored, not refused**: an old Mini App build keeps working.
A new stadium starts with `cancellationPolicy.cancellationHours = 24` and no
`minBookingAmount` (full payment) until an admin sets the terms.

## 2. `PUT /api/owner/stadiums/{id}` (update) — request

Partial: only non-null fields are applied, each checked with the **same rules and messages
as create**. Differences:

- `district`, `phone`, `latitude`/`longitude` are optional; `latitude` and `longitude` must
  come **together** ("Send both latitude and longitude").
- `landmark`: sent blank (`""` or spaces) **clears it**; absent leaves it.
- `openTime` / `closeTime`: either may be sent alone; the pair that would result (sent value,
  else the current one) must be valid.
- `amenities`: when sent, the whole block replaces the old one (send all 12 flags; the
  Mini App shows 10 and must send `wifi` / `cafe` back unchanged).
- `minBookingAmount`, `cancellationHours`, `clearMinBookingAmount`, `photos`: **removed,
  ignored if sent**. The old 400 "Send either minBookingAmount or clearMinBookingAmount"
  no longer exists.

## 3. NEW `PUT /api/admin/stadiums/{id}/booking-terms` (admin only)

Request (the whole terms each time):

```json
{ "minBookingAmount": 5000000, "cancellationHours": 12 }
```

| Field | Rule |
|---|---|
| `minBookingAmount` | `Long` or `null`. `null` / absent = full payment. Else ≥ 50 000 and ≤ the stadium's `pricePerHour` (the owner's price) |
| `cancellationHours` | `Integer`, **required**, 0–168 |

- Any stadium status. Answer: **the same object as `GET /api/admin/stadiums/{id}`**, message
  "Booking terms updated".
- 401 without a token, 403 for non-admins, 404 "Stadium not found".
- Audit log: new action code **`STADIUM_BOOKING_TERMS_CHANGED`** (actor type ADMIN,
  changes `minBookingAmount` / `cancellationHours` old → new; no entry when nothing
  changed). It also appears in `GET /api/admin/audit-log/actions`.

The owner sees the terms read-only in their stadium detail: `minBookingAmount`,
`playerMinBookingAmount`, `cancellationPolicy.cancellationHours`.

## 4. Responses — new fields

| Response | New field |
|---|---|
| `StadiumDetailResponse` (player `GET /api/stadiums/{id}`, owner `GET`/`POST`/`PUT /api/owner/stadiums…`, admin `GET /api/admin/stadiums/{id}` and booking-terms) | `landmark` (string, **absent when none**) |
| `StadiumResponse` (player list, favorites, owner list `GET /api/owner/stadiums`) | `landmark` (absent when none) |
| `amenities` (in every detail) | `toilet`, `covered` (covered/indoor), `bibs`, `drinkingWater` — booleans, always present |

Full amenities object now: `parking`, `lighting`, `showers`, `lockerRoom`, `wifi`, `cafe`,
`equipmentRental`, `tribune`, `toilet`, `covered`, `bibs`, `drinkingWater`.

The admin detail already carried (unchanged): `photos`, `photoUrl`, `latitude`,
`longitude`, `amenities`, `cancellationPolicy`, `cancellationHours`, `minBookingAmount`,
`playerMinBookingAmount`, `ownerContractStatus`.

## 5. Stadium photos — `POST /api/owner/stadiums/{id}/photos` and friends

| What | Before | Now |
|---|---|---|
| Who may call upload / delete / reorder | owner role only | **any signed-in user**; still 404 "Stadium not found" / 403 "You do not own this stadium" — a player can upload the photos of their own pending stadium right after creating it |
| Max file size | 10 MB | **5 MB** → **413** "File too large" (also for a multipart body over 6 MB) |
| Accepted | JPEG/PNG/WebP by bytes | same, and the file must actually decode |
| Stored as | the original bytes, `.jpg`/`.png`/`.webp` | **always re-encoded JPEG** (`.jpg`), long side ≤ 2000 px, quality 0.85, **no EXIF** (no GPS/camera), transparency on white |
| `PhotoResponse` | `{ id, url, position }` | unchanged; `url` now always ends in `.jpg` (older photos keep their URLs) |

EXIF orientation is **not** applied by the server: rotate the image on the device before
uploading (D18: `createImageBitmap(..., { imageOrientation: 'from-image' })`).

## 6. Approval — `PUT /api/admin/stadiums/{id}/status` → `approved`

Now also needs **at least 3 photos** (checked after the ATMOS contract), for every move into
`approved` (from pending, rejected or blocked). Request and response unchanged.

## 7. Every new or changed error message

| Endpoint | Status | Message |
|---|---|---|
| create | 400 | `name is required` |
| create / update | 400 | `name must be between 3 and 150 characters` |
| create | 400 | `address is required` |
| create / update | 400 | `address must be between 5 and 300 characters` |
| create | 400 | `district is required` |
| create / update | 400 | `Invalid district` (unchanged) |
| create | 400 | `phone is required` |
| create / update | 400 | `phone must be in the format +998XXXXXXXXX` |
| create / update | 400 | `description must be at most 500 characters` |
| create / update | 400 | `landmark must be at most 150 characters` |
| create | 400 | `latitude and longitude are required` |
| update | 400 | `Send both latitude and longitude` |
| create / update | 400 | `Location must be inside Uzbekistan` |
| create | 400 | `pricePerHour is required` |
| create / update | 400 | `pricePerHour must be between 10 000 and 5 000 000 so'm` |
| create | 400 | `openTime is required` / `closeTime is required` |
| create / update | 400 | `openTime and closeTime must be on the hour` |
| create / update | 400 | `closeTime must be after openTime; closing after midnight is not supported` |
| booking-terms | 400 | `minBookingAmount must be at least 50000 tiyin (500 so'm)` |
| booking-terms | 400 | `minBookingAmount must not be more than pricePerHour` |
| booking-terms | 400 | `cancellationHours is required` |
| booking-terms | 400 | `cancellationHours must be between 0 and 168` |
| booking-terms | 404 | `Stadium not found` |
| photo upload | 413 | `File too large` (now over 5 MB) |
| photo upload | 400 | `Unsupported image type` (now also: right magic bytes but undecodable) |
| photo upload | 400 | `Image is too large` (**new**: over 40 megapixels) |
| status → approved | 400 | `A stadium needs at least 3 photos to be approved` (**new**) |
| owner update | — | `Send either minBookingAmount or clearMinBookingAmount` — **removed** |

Validation order on create (first failure wins): name, address, district, phone,
description, landmark, location, price, hours. `format` / `surfaceType` are bean-validated
before all of them (`"format: format must be one of: 5x5, 7x7, 9x9, 11x11"`, unchanged).
