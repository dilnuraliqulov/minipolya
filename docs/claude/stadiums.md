# Stadiums

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching stadium creation, review/approval, districts, amenities or the public stadium list.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Stadiums

- 1 stadium = 1 pitch (format, surface_type on stadiums table)
- Statuses: `pending` → `approved` or `rejected` (turned down at review, only from
  pending); `approved` → `blocked` (taken off after approval) → `approved` again
  (unblocked). Rejecting or blocking needs a reason, kept in `stadiums.status_reason`
  and shown to the owner as `statusReason` on the owner endpoints; approving or setting
  back to pending clears it. Blocking cancels future bookings with full refunds;
  rejecting has none to cancel.
- Only `approved` (and active) stadiums are visible to players — rejected and blocked
  ones never are.
- **The player list** (`GET /api/stadiums`, public; `GET /api/favorites` is the same card):
  filters `q`, `format`, `maxPrice` (a player price) and `district` (one of the twelve,
  else 400 "Invalid district"), all optional — a JPA specification
  (`StadiumSpecifications.publicFilter`). Each `StadiumResponse` carries
  `coverPhotoUrl` (the lowest-position photo; absent when none), `averageRating` (absent
  with no reviews), `reviewCount`, `freeSlotsCount` (see "Availability Calculation") and for a
  signed-in caller `isFavorited`. Ratings,
  cover photos and favorites are loaded for the whole page in one grouped query each
  (`StadiumService.toPublicResponses`; `PublicStadiumListTest` counts the queries with 22
  stadiums). The detail (`GET /api/stadiums/{id}`) carries `averageRating` /
  `reviewCount` too.
- **Anyone signed in can add a stadium** (R42): `POST /api/owner/stadiums` and
  `GET /api/owner/stadiums` need only a token, not `isOwner`, so a player adds their first
  stadium and sees it (with `status` / `statusReason`) in their list. It starts `pending`;
  approval sets `is_owner`, and the user needs a fresh token (the flag is a JWT claim) for
  the rest of `/api/owner/**`, which stays owner-only.
- Owner adds stadium → admin reviews (`GET /api/admin/stadiums/{id}` works in any
  status) → approves or rejects.
- **Approval needs the owner's ATMOS contract signed** (`owner_legal_details`, see
  "Owner legal details"): otherwise `PUT /api/admin/stadiums/{id}/status` answers 400
  "The owner's ATMOS contract is not signed". **Then at least 3 photos** (checked after
  the contract): otherwise 400 "A stadium needs at least 3 photos to be approved"
  (`AdminService.MIN_PHOTOS_TO_APPROVE`; at most 10 as before). Both apply to every move
  into `approved` (from pending, rejected or blocked), not to re-sending `approved`. On
  approval: status → approved, user.is_owner → true.
- **What an owner sends** (`POST` / `PUT /api/owner/stadiums`), checked by **one helper,
  `util/StadiumRules`**, so create and update agree — every message is a 400 in the
  envelope. On create every required field must be there; on update only what is sent is
  checked (old stadiums that break a rule can still be edited elsewhere):
  - `name` trimmed 3–150 ("name is required" / "name must be between 3 and 150
    characters"); `address` trimmed 5–300 (same wording);
  - `district` **required on create** ("district is required"), see below;
  - `phone` **required on create** ("phone is required"), `^\+998\d{9}$` — "phone must
    be in the format +998XXXXXXXXX" (the profile's rule);
  - `description` ≤ 500 ("description must be at most 500 characters");
  - `landmark` ("Moʻljal", changeset 048) optional, trimmed, ≤ 150 ("landmark must be at
    most 150 characters"); blank is none, and on update clears it;
  - `latitude` / `longitude` **required on create** ("latitude and longitude are
    required"), on update both or neither ("Send both latitude and longitude"); inside
    Uzbekistan's box, lat 37.1–45.6, lon 55.9–73.2 — "Location must be inside Uzbekistan";
  - `pricePerHour` 1 000 000–500 000 000 tiyin — "pricePerHour must be between 10 000 and
    5 000 000 so'm" ("pricePerHour is required" on create);
  - the **opening hours**: either `hours` (the whole week) or `openTime` / `closeTime`
    (the same every day), never both ("Send either hours or openTime and closeTime, not
    both"); on update the pair goes together ("Send both openTime and closeTime") and
    sending neither leaves the stored schedule alone. See "Opening hours" below for the
    rules and the messages.
  - **Not the owner's**: `minBookingAmount` / `cancellationHours` are the admin's
    (`PUT /api/admin/stadiums/{id}/booking-terms`), and photos come only through the
    upload endpoint. Those fields (and `clearMinBookingAmount`, `photos`) are gone from
    `CreateStadiumRequest` / `UpdateStadiumRequest`; an older client still sending them is
    **ignored, not refused** (unknown JSON properties are ignored).
- **Amenities** (`amenities` on create/update, all booleans, any omitted one false — the
  block replaces the old one when sent): `parking`, `lighting`, `showers`, `lockerRoom`,
  `wifi`, `cafe`, `equipmentRental`, `tribune`, and since changeset 048 `toilet`,
  `covered`, `bibs`, `drinkingWater`. `AmenitiesRequest` keeps its all-args constructor
  private so Jackson binds through the setters (otherwise a block missing a flag is a 400).
- `landmark` and `amenities` are in every stadium detail (player, owner, admin);
  `landmark` also in the list cards (`StadiumResponse`), absent when none.
- `district`: one of the twelve Tashkent districts — `Bektemir`, `Chilonzor`,
  `Mirobod`, `Mirzo Ulugʻbek` (with U+02BB, not an apostrophe), `Olmazor`, `Sergeli`,
  `Shayxontohur`, `Uchtepa`, `Yakkasaroy`, `Yangihayot`, `Yashnobod`, `Yunusobod`
  (`util/Districts`, CHECK `chk_stadiums_district`). Required when the owner creates a
  stadium, optional on update; anything else is 400 "Invalid district". Null on stadiums
  added before it existed.

