# Availability calculation

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching the availability endpoint, slot statuses or the free-slot count.**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Availability Calculation

Every opening hour of the **working day** `date` is listed, each with a `status`. The day
may end on the next calendar date — see "Opening hours and the working day".
1. Get the working day's hours from `stadium.weeklyHours().dayOf(date)` (a closed day has none)
2. Cut into 1-hour slots, opening first, so a day closing at 02:00 ends …, 23:00, 00:00, 01:00
3. An hour that has already started is `past` (compared on the real instant, so an
   after-midnight hour is past only once it has actually begun)
4. A slot that overlaps any `pending` or `confirmed` booking is `booked`
5. A slot within a `stadium_closure` is `closed`; every other one is `free`
   (`available` is true exactly when `status = free`; precedence past → booked → closed)
6. For each free slot, calculate price from `pricing_rules` (match the **working day's**
   day_of_week + the slot's own time range)
7. Mark slots that have a WAITING **team_vs_team** game (show "a team is looking for an opponent" in UI). Casual games are private to whoever was invited and are never marked
8. Apply any active promotion discount
9. When the deposit of the stadium's effective minimum is below the slot's player price,
   add `depositAmount` / `remainderAmount` (E3, T3; see "Payments → API fields")
10. Every slot carries `startsAt` (the real UTC instant) and, when it falls after midnight,
    `afterMidnight: true`

A date whose working day is **still running** is not refused as past: at 00:30 the previous
day is still open if any of its hours is ahead, so its 01:00 slot can still be booked
(`AvailabilityService.earliestWorkingDay`).

**Free-slot count.** `GET /api/stadiums?date=YYYY-MM-DD` (Tashkent, default today; malformed = 400)
answers `freeSlotsCount` on each stadium: the hours of that date whose status above would be
`free`, from the one definition in `SlotRules`, which the availability endpoint uses too, so the
two cannot disagree. Three queries for the whole page — booked spans, closures and the
weekly schedules (`AvailabilityService.freeSlotCounts`; `StadiumFreeSlotsTest` counts them
with 22 stadiums). A date outside the booking window (past,
or beyond `max-weeks-ahead`) answers 0, not an error, so a list left open past midnight still loads.

