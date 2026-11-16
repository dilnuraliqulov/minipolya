# Spec — language flags, and opening hours past midnight per day of the week

The request this plan implements, verbatim. Two changes, in BOTH repos
(`minipolyaBackend`, `minipolyaFrontend`), following each repo's CLAUDE.md (new
Liquibase changesets only; never push or create branches; never read or edit `.env`
files). Commit per step with checks passing.

## 1. Language flags (Mini App and admin language pickers)

Small inline SVG flags next to each language: Uzbekistan for Oʻzbekcha, Russia for
Русский, United Kingdom for English. NOT emoji flags (they don't render on Windows).
Same size and alignment as the existing radio rows, decorative (`aria-hidden`), light
and dark.

## 2. Opening hours past midnight, per day of the week

### Rules

- A stadium has a weekly schedule: for each day of the week (0 = Monday) either
  "closed" or open/close times on whole hours. Close may be after midnight: close ≤ open
  means the next morning (e.g. 08:00–02:00), up to 06:00 at the latest; close = 00:00
  means midnight. At least 1 hour between a day's closing and the next day's opening;
  never more than 24 hours open.
- A "working day" = the day the opening happened. Slots after midnight belong to the
  previous working day: Saturday 08:00–02:00 shows on Saturday's list as …, 23:00,
  00:00, 01:00.
- Bookings keep storing the REAL start/end instants (Sunday 00:00–01:00 in
  Asia/Tashkent, as UTC), so the EXCLUDE constraint, payments, cancellation deadlines,
  the 3-hour rule, reminders and expiry jobs need no special cases. Verify each with a
  test around midnight.
- Pricing rules and promotions match by the WORKING day's day-of-week and the slot's
  clock time (Saturday's 00:00 slot uses Saturday's price rules for 00:00–01:00).
- A closure on date D covers its whole working day, including the slots after midnight.
- Availability for date D returns the working day's slots (D open → close, possibly
  ending on D+1), each with its real start time and an "afterMidnight" flag.
  `freeSlotsCount` on the stadium list uses the same rule.

### Backend

- A new changeset for the weekly schedule; migrate existing stadiums' open/close into
  "same every day"; keep the old fields working until the frontend switches (or update
  all readers in this task).
- Validation per the rules above, clear error messages; the owner's create/update, and
  the admin can also edit the schedule (audited).
- Tests: Friday 08:00–02:00 and Monday 08:00–00:00; a booking at 01:00 on the working
  day; overlap with the next day's opening refused; a closure covering the night; prices
  for after-midnight slots; the 24h cancellation and 3-hour rules near midnight; the
  reminder job.

### Mini App

- Owner (wizard step "Ish vaqti va narx" and Maʼlumotlar): "Har kuni bir xil" (default)
  or "Har kun alohida" (7 rows: Du, Se, Ch, Pa, Ju, Sh, Ya, each with a "Yopiq" switch,
  opening and closing time). Closing times include 00:00 and 01:00–06:00 shown as
  "00:00 (ertasi kuni)". The summary line shows e.g. "Ju–Sh 08:00–02:00, boshqa kunlar
  08:00–00:00". Remove "Yarim tundan keyin yopilish qoʻllab-quvvatlanmaydi".
- Players (slot picker): after-midnight slots appear at the end of the working day with
  a small "tun" label; the booking summary and booking detail show the real date:
  "Yakshanba, 16-noyabr · 00:00–01:00 (shanba kechasi)".
- Admin stadium page: show the weekly schedule; edit it with confirmation.

Check in Chrome (localhost:5173) at 390×844, light and dark: set Friday to 08:00–02:00,
book 01:00 as a player, see it in Oʻyinlarim with the real date, cancel it.

Update CLAUDE.md (the working-day rule) and the Postman collection.
