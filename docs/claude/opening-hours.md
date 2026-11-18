# Opening hours and the working day

> Part of the MiniPolya CLAUDE.md doc set. **Read this before touching opening hours, the weekly schedule, or anything that asks "which day is this hour?".**
> The hard rules stay in `CLAUDE.md`. A quoted cross-reference (`see "X"`) may point at a
> section in another file in this folder — the map is [README.md](README.md).

## Opening hours and the working day

A stadium's hours are a **weekly schedule** (`stadium_hours`, changeset 053): one row per
day of the week, `day_of_week` 0 = Monday … 6 = Sunday, each either closed or open between
two whole hours. **`util/WeeklyHours` is the one place the rule is expressed** — everything
that asks "which day is this hour?" goes through it.

- A `closeTime` **after** `openTime` is the same day. At or before it, the day runs **past
  midnight** into the next morning and must close by **06:00**
  ("A closing time at or before the opening time means the next morning, so it must be
  06:00 or earlier"). `00:00` means **midnight** — the end of that same day, with no hours
  after it. A day is open at most 24 hours.
- Both times on the hour ("openTime and closeTime must be on the hour"); all seven days,
  each exactly once ("hours must list all 7 days of the week exactly once", "dayOfWeek must
  be between 0 and 6"); an open day needs both times ("openTime and closeTime are required
  on an open day"); at least one day open ("At least one day must be open").
- At least **1 hour** between a day's closing and the next day's opening, around the week:
  "There must be at least 1 hour between Friday's closing and Saturday's opening".

**The working day is the day whose opening started the hour.** The hours after midnight
belong to it, not to the calendar day they fall on: with Saturday 08:00–02:00, Saturday's
list ends …, 23:00, **00:00, 01:00**, and those two hours happen on Sunday's date. So:

- **Bookings store the real instants** (`starts_at` / `ends_at`), exactly as before. The
  EXCLUDE constraint, payments, refunds, the cancellation deadline, the 3-hour opponent
  cutoff, `BookingExpiryJob` and `ReminderJob` all work on instants and need **no special
  case** — `MidnightBookingRulesTest` proves it.
- **Availability for date D** lists D's working day, which may end on D+1. Each slot carries
  `startTime` / `endTime` (wall clock), `startsAt` (the real UTC instant) and
  `afterMidnight` (only when true). `freeSlotsCount` uses the same `SlotRules`.
- **Pricing rules and promotions match the WORKING day's weekday** and the slot's own clock
  time: Saturday's 00:00 slot takes Saturday's rules for 00:00–01:00, never Sunday's.
- **A closure on date D covers its whole working day**, the hours after midnight included
  (the closure is asked about D with the hour's clock time, so a full-day closure on D
  closes the night too).
- **A booking reports its working day**: `workingDate` and `afterMidnight` on every
  `BookingResponse`, so a client can say "Yakshanba, 16-noyabr · 00:00–01:00 (shanba
  kechasi)" without deriving the rule.
- **A recurring series' `dayOfWeek` is the working day** too: a 01:00 slot of Wednesday is
  booked at 01:00 on the Thursday date.
- An hour that belongs to **no** working day is 400 "The stadium is not open at that hour"
  (`BookingWindowValidator`) — the three old same-day messages are gone.
- `stadiums.open_time` / `close_time` **stay**, kept in step with the first open day, so
  older readers and clients still see one sensible pair. Read the schedule through
  `Stadium.weeklyHours()`, which falls back to that pair as "the same every day" when the
  seven rows are missing; write it through `Stadium.setWeeklyHours`, which updates the rows
  in place (clearing and re-adding them trips `uq_stadium_hours_day`, because Hibernate
  flushes inserts before orphan deletes). A page of stadiums loads every schedule in **one**
  grouped query (`StadiumHoursRepository.findByStadiumIdIn`), never seven rows per stadium.

