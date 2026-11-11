# Rework "Maydon qoʻshish" (add stadium) — design

Date: 2026-11-06 · Repos: `minipolyaBackend` (first), then `minipolyaFrontend`.
The user asked for no stop-and-ask, so every open point is decided below and logged.

## Goal

An owner (or a player adding a first stadium, R42) creates a complete, reviewable stadium from
a phone inside Telegram in one go: contact phone, exact map pin, district, 3–10 photos,
facilities, hours and price. Booking terms (deposit, cancellation hours) move to the admin,
agreed by phone. Players see facilities, landmark and open the pin in Yandex/Google Maps.

## Decision log

| # | Decision | Why |
|---|---|---|
| D1 | `phone` required on create, `^\+998\d{9}$` ("phone must be in the format +998XXXXXXXXX", the user-profile message). On update optional, same pattern when sent. Existing stadiums keep theirs. | User decision 1; same rule as `PATCH /api/users/me`. |
| D2 | `minBookingAmount`, `cancellationHours`, `clearMinBookingAmount` **removed** from `CreateStadiumRequest` / `UpdateStadiumRequest`. A client still sending them is ignored (Jackson ignores unknown properties), not 400, so an old Mini App build does not break. | User decision 2. |
| D3 | New `PUT /api/admin/stadiums/{id}/booking-terms` `{ minBookingAmount: Long?, cancellationHours: Integer }` — `minBookingAmount` null = full payment, else ≥ 50 000 tiyin (existing floor) and ≤ `pricePerHour`; `cancellationHours` 0–168. Any status. Audited `STADIUM_BOOKING_TERMS_CHANGED` (ADMIN, old → new). Returns the admin stadium detail. | User decision 2. 168 h (a week) is a sane upper bound. |
| D4 | Defaults stay where CLAUDE.md puts them: `cancellation_hours` column/entity default 24, `min_booking_amount` null. **No new `platform_settings` columns.** | CLAUDE.md: "The default of 24 lives in exactly two places … don't reintroduce one". "Platform settings (24 h, no minimum)" are exactly these values. |
| D5 | Photo upload: ≤ **5 MB** (service + `spring.servlet.multipart.max-file-size: 5MB`, request 6MB), type from magic bytes (JPEG/PNG/WebP). The server **decodes and re-encodes every photo to JPEG (quality 0.85)**, downscaling to ≤ 2000 px on the long side; re-encoding writes no metadata, so EXIF (GPS, camera) is stripped. Stored as `<uuid>.jpg`. WebP decoding via `com.twelvemonkeys.imageio:imageio-webp` (read-only ImageIO plugin). Images over 40 megapixels (read from the header before decoding) → 400 "Image is too large" (decompression-bomb guard). Undecodable → 400 "Unsupported image type". | User decision 3; never trust client name/type. JPEG output keeps one code path and small files. |
| D6 | Photo endpoints (`POST/DELETE /api/owner/stadiums/{id}/photos…`, `PUT …/photos/order`) open to **any signed-in user**, ownership still checked (404/403), like `POST /api/owner/stadiums` (R42). | A player adding a first (pending) stadium has no `isOwner` yet but must upload its photos in the wizard. |
| D7 | Approval (`PUT /api/admin/stadiums/{id}/status` → `approved`) needs **≥ 3 photos**: 400 "A stadium needs at least 3 photos to be approved". Max stays 10. | Makes "at least 3" a real rule; creation cannot carry photos (they upload after create). |
| D8 | The `photos` URL list is **removed** from create/update requests — photos come only through the upload endpoint. | Otherwise any URL bypasses D5. |
| D9 | Amenities already present: `parking`, `lighting` (night play), `showers`, `lockerRoom` (changing room), `equipmentRental` (ball rental), `tribune` (spectator seating), `wifi`, `cafe`. **Added** (changeset 048): `toilet`, `covered` (covered/indoor), `bibs`, `drinkingWater`. `wifi` / `cafe` stay in the API; the Mini App shows the 10 requested ones and sends the other two back unchanged. | User decision 4; never drop data. |
| D10 | "Qoʻshimcha maʼlumot" = existing `description`, `@Size(max = 500)` on create/update. | User decision 4: reuse. |
| D11 | `landmark` ("Moʻljal") new nullable `VARCHAR(150)` (changeset 048), optional, trimmed, blank → null; in every stadium detail response and owner list. | User decision 5. |
| D12 | `latitude` / `longitude` **required on create**; on update both or neither. Bounds: Uzbekistan's bounding box, lat 37.1–45.6, lon 55.9–73.2 → else 400 "Location must be inside Uzbekistan". | User decision 5. |
| D13 | Validation (create; on update for each field sent): `name` trimmed 3–150; `address` trimmed 5–300; `district` **required on create**, one of the 12; `pricePerHour` 1 000 000–500 000 000 tiyin (10 000–5 000 000 soʻm) → "pricePerHour must be between 10 000 and 5 000 000 so'm"; `openTime` < `closeTime`, both on the hour. | User backend rules. |
| D14 | **Closing after midnight is not supported** by availability (`AvailabilityService` walks `open → close` in one day; `BookingWindowValidator` refuses `open ≥ close`). So `closeTime <= openTime` → 400 "closeTime must be after openTime; closing after midnight is not supported". Reported to the user. | User: "refuse it with a clear error and tell me". |
| D15 | **Hour steps, not 30-minute steps**, in the time pickers. Bookings must start on the hour (`BookingWindowValidator`: "Booking must start on the hour") and slots are cut from `openTime`, so 08:30 opening would give a pitch with unbookable slots. Backend refuses times not on the hour: "openTime and closeTime must be on the hour". Pickers are custom 24-hour lists (00:00–23:00), never native AM/PM. Reported to the user. | Deviation from the brief, forced by the booking rules. |
| D16 | Wizard draft lives in the wizard's React state (one `useReducer`), so it survives Back/Next and Telegram's back button; photos stay as in-memory `File`s with object-URL thumbnails (revoked on unmount). No storage persistence (files cannot be stored there). | Simple, meets "survives going back and forth". |
| D17 | Submit: create → upload photos one by one with per-photo progress (XHR `upload.onprogress`) → success screen "Arizangiz qabul qilindi" only when all are uploaded. Failed ones show a red state and "Qayta urinish"; leaving is allowed, photos can be finished in Maydonim → Rasmlar. Retrying never creates the stadium twice (the created id is kept). | User brief. |
| D18 | Shared components: `PhotoPicker` (wizard: local files; Rasmlar: server photos) and `LocationPicker` (wizard + Maʼlumotlar). Pure helpers with unit tests: `downscaleImage` (canvas → JPEG 0.85, ≤ 2000 px; `createImageBitmap` with `imageOrientation: 'from-image'` so EXIF rotation is baked in), `parseMapLink` (Google `@lat,lon`, `q=`/`query=`/`ll=`, `!3dlat!4dlon`; Yandex `ll=lon,lat`, `pt=lon,lat`, `whatshere[point]=lon,lat`; short links → `null` with "Toʻliq havolani qoʻying yoki xaritada belgilang"), `facilities` list (key → icon + label). | User: "one shared component". |
| D19 | "Joriy joylashuvim": Telegram `LocationManager` (Bot API 8.0: `init` → `isLocationAvailable` → `getLocation`) when present, else `navigator.geolocation`; refused/unavailable → Uzbek message. Zoom 17 after location or a parsed link; Tashkent centre `41.2995, 69.2401`, zoom 12. | User brief. |
| D20 | Player stadium page: facilities with icons, Moʻljal, buttons "Yandex Maps'da ochish" `https://yandex.uz/maps/?pt=<lon>,<lat>&z=17&l=map` and "Google Maps'da ochish" `https://www.google.com/maps/search/?api=1&query=<lat>,<lon>` via `Telegram.WebApp.openLink` (falls back to `window.open`). | User brief. |
| D21 | Owner mode: deposit/cancellation editing removed; a read-only "Bron shartlari" card with "Platforma bilan kelishilgan". Admin stadium page: "Bron shartlari" card (edit + confirm dialog), facilities, Moʻljal, photos, map. | User brief. |

## Backend changes (summary)

- Changeset `048-stadium-wizard-fields.sql`: `stadiums.landmark VARCHAR(150)`; `stadium_amenities` `toilet`, `covered`, `bibs`, `drinking_water` BOOLEAN NOT NULL DEFAULT FALSE.
- DTOs (D1–D3, D8–D13), `StadiumService` validation (one `StadiumRules` helper so create and update agree), audit snapshot gets `landmark` and the 4 amenities, `StadiumPhotoService` + `ImageReencoder` (D5), `SecurityConfig` (D6), admin approval rule (D7), admin booking-terms endpoint (D3).
- Tests for every rule; CLAUDE.md, Postman, `docs/frontend-field-changes-2026-11-06.md` (the changed fields for the frontend).

## Mini App wizard

Steps with a progress bar (6 segments, animated fill) and a slide/fade transition between steps
(respecting `prefers-reduced-motion`):

1. **Asosiy** — name, format, surface, phone (required; `+998 XX XXX XX XX` mask).
2. **Joylashuv** — `LocationPicker`, Tuman (required, no "—" default; placeholder "Tumanni tanlang"), Manzil, Moʻljal.
3. **Rasmlar** — `PhotoPicker`, 3–10, first = cover, reorder, delete.
4. **Qulayliklar** — facility chips with icons (toggle, `aria-pressed`), Qoʻshimcha maʼlumot (textarea, counter /500).
5. **Ish vaqti va narx** — 24-hour hour pickers; "Sizning narxingiz" + note that players see a slightly higher price (card fee).
6. **Koʻrib chiqish** — everything, photo thumbnails, small static map preview, facilities, note "Oldindan toʻlov va bekor qilish shartlari platforma bilan kelishiladi."

Validation messages in Uzbek (ru/en too) before "Davom etish", mirroring D12–D15. Then "Tekshiruvga yuborish" → D17.

## Photos (`PhotoPicker`)

`<input type="file" accept="image/*" multiple>`; thumbnails immediately; each file downscaled on
the device before upload; non-images → "Bu fayl rasm emas"; > 5 MB after downscaling →
"Rasm 5 MB dan katta"; more than 10 → "Koʻpi bilan 10 ta rasm". Cover badge, move left/right,
"Muqova qilish", delete; 2-column grid of 4:3 tiles, ≥ 44 px targets.

## Map (`LocationPicker`)

Leaflet + OSM, lazy-loaded. Tap to move the pin, drag it; "Joriy joylashuvim"; link field
"Google yoki Yandex xarita havolasini qoʻying"; coordinates small under the map
(`41.29950, 69.24010`). Map card with rounded corners, the pin as the existing marker.

## Testing

Backend: `./mvnw test` (DB tests included). Frontend: unit tests for helpers and components
(Vitest + Testing Library), `typecheck`, `lint`, `format:check`, `build`. Visual check in Chrome
at 360×640 and 390×844, light and dark, every wizard step, the player page, owner pages, admin card.
