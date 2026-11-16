# Weekly Opening Hours Past Midnight + Language Flags — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A stadium gets a per-weekday opening schedule whose closing time may fall after midnight (up to 06:00), with after-midnight slots belonging to the *working day* that opened them, everywhere in the backend, the Mini App and the admin panel; and the three language pickers get inline SVG flags instead of emoji.

**Architecture:** One new table `stadium_hours` (7 rows per stadium, `day_of_week` 0 = Monday) plus one immutable value object `util/WeeklyHours` that is the single place the working-day rule is expressed. Every existing reader of `stadiums.open_time` / `close_time` moves onto it; the two legacy columns stay NOT NULL and keep being written (from the first open day) so older clients and the Postman collection keep working. Bookings keep storing the real UTC instants, so the EXCLUDE constraint, payments, cancellation and the jobs need no special cases — only tests to prove it. On the client, `shared/flags` holds three inline SVG flags and `miniapp/owner/hours.ts` mirrors the backend rules for pre-submit feedback.

**Tech Stack:** Java 21 / Spring Boot 4.1.1 / PostgreSQL / Liquibase / MapStruct · React 19 / TypeScript 6 / Tailwind 4 / i18next / Vitest

**Spec:** the user's two-part request, reproduced verbatim in `docs/superpowers/plans/2026-11-16-weekly-hours-spec.md`

## Global Constraints

- Liquibase: **new changesets only** (053+), added to `db.changelog-master.yaml`; never edit an applied one.
- Never read or edit `.env` in either repo. Never create a branch, push, or open a PR. Commit per task on the current branch.
- The local backend runs with `SPRING_PROFILES_ACTIVE=dev` and `SPRING_DEVTOOLS_RESTART_ENABLED=false`; **stop it before `mvnw clean` or `mvnw test`**, restart after.
- `dayOfWeek` is **0 = Monday … 6 = Sunday** in the database, the API and the frontend.
- All timestamps TIMESTAMPTZ/UTC on the wire, displayed in `Asia/Tashkent`; all money BIGINT tiyin.
- Backend error messages are English prose, thrown as `BadRequestException` → 400 in the envelope.
- Mini App text goes through i18next in all three of `uz`/`ru`/`en`; Uzbek uses `ʻ` U+02BB and `ʼ` U+02BC. New keys get listed in `minipolyaFrontend/docs/translations-to-review.md`.
- Mini App and admin colours come only from the design tokens — no hex in components. Every screen checked light **and** dark.
- Frontend never re-implements a business rule: prices, deadlines and the `afterMidnight` flag come from the API.
- Before each frontend commit: `npm run typecheck`, `npm run lint`, `npm run test`, `npm run build`.
- The schedule rules, exactly:
  - each day is either `closed`, or `openTime`/`closeTime` both on the hour;
  - `closeTime > openTime` → same day; `closeTime <= openTime` → the next morning and `closeTime` must be **≤ 06:00**; `closeTime = 00:00` means midnight (24:00);
  - a day is open at most **24 hours**;
  - at least **1 hour** between a day's closing and the next day's opening;
  - at least one day must be open.

## Review Focus

1. **A stadium whose `stadium_hours` rows are missing** (a row inserted by hand, a future bug) — `WeeklyHours.of` must fall back to the legacy `open_time`/`close_time` as "same every day" rather than throw, so no stadium becomes unbookable. *Test in Task 2.*
2. **A booking at 23:00 on a day closing at 00:00** — the hour ends exactly at midnight on the next calendar date; `endsSameDay`-style checks must not refuse it. *Test in Task 4.*
3. **Availability for date D when D is today and now is 00:30** — D's after-midnight slots have already started and must be `past`, while D-1's night slots are a different working day entirely. *Test in Task 5.*
4. **A pricing rule or promotion covering 00:00–02:00 on Sunday** when the slot is Saturday's night — it must **not** match (Saturday's rules apply). *Test in Task 6.*
5. **Daylight-saving-free but offset-sensitive arithmetic** — Tashkent is UTC+5 year round, but the working-day resolution must go through `ZonedDateTime`, never a hard-coded +5, so a 00:00 slot converts to `19:00Z` the previous day. *Test in Task 2.*

---

## Part A — Language flags (both repos, independent of Part B)

### Task 1: Inline SVG language flags

**Files:**
- Create: `minipolyaFrontend/src/shared/flags/index.ts`
- Create: `minipolyaFrontend/src/shared/flags/LanguageFlag.tsx`
- Create: `minipolyaFrontend/src/shared/flags/flags.test.tsx`
- Modify: `minipolyaFrontend/src/miniapp/components/OptionList.tsx`
- Modify: `minipolyaFrontend/src/miniapp/profile/SettingsSheets.tsx` (the `LANGUAGES.map` options)
- Modify: `minipolyaFrontend/src/admin/pages/users/UserPage.tsx:258` (the `Til` row)

**Interfaces:**
- Consumes: nothing.
- Produces: `LanguageFlag({ language, className }: { language: 'uz' | 'ru' | 'en'; className?: string })` → `JSX.Element`; `FlagLanguage` type alias. `Option<T>` in `OptionList` gains `leading?: ReactNode`.

- [ ] **Step 1: Write the failing test** — `shared/flags/flags.test.tsx`

```tsx
it.each(['uz', 'ru', 'en'] as const)('renders a decorative inline svg for %s', (language) => {
  const { container } = render(<LanguageFlag language={language} />)
  const svg = container.querySelector('svg')!
  expect(svg).toHaveAttribute('aria-hidden', 'true')
  expect(svg.getAttribute('viewBox')).toBe('0 0 20 14')
  expect(container.textContent).toBe('') // not an emoji flag
})

it('uses the border token for its hairline, not a hard-coded colour', () => {
  const { container } = render(<LanguageFlag language="uz" />)
  expect(container.querySelector('rect.stroke-border')).not.toBeNull()
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd minipolyaFrontend && npx vitest run src/shared/flags/flags.test.tsx`
Expected: FAIL — cannot resolve `./LanguageFlag`.

- [ ] **Step 3: Implement `LanguageFlag` in `shared/flags/LanguageFlag.tsx`**

One component, a `switch` on `language`, each branch a `<g>` of `<rect>`/`<path>`: Uzbekistan — bands `#0099B5` / `#FFFFFF` / `#1EB53A` with two `#CE1126` hairlines, a white crescent and three stars; Russia — `#FFFFFF` / `#0039A6` / `#D52B1E`; United Kingdom — `#012169` ground, white then `#C8102E` diagonals, white then `#C8102E` cross. `viewBox="0 0 20 14"`, `width="20" height="14"`, `aria-hidden="true"`, `focusable="false"`, `className={cx('shrink-0 rounded-xs', className)}`, and last a `<rect x="0.5" y="0.5" width="19" height="13" rx="2" fill="none" className="stroke-border" strokeWidth="1" />`. These are flag colours, not theme colours, so hex inside this file is correct and the file carries a comment saying so.

- [ ] **Step 4: Run it and watch it pass**

Run: `cd minipolyaFrontend && npx vitest run src/shared/flags/flags.test.tsx`
Expected: PASS.

- [ ] **Step 5: Write the failing tests for the two pickers**

In `miniapp/profile/ProfilePage.test.tsx`, add to the language-sheet block:

```tsx
it('shows a flag beside each language', async () => {
  // open Profil → Til as the existing tests do
  const group = screen.getByRole('radiogroup', { name: 'Tilni tanlang' })
  expect(group.querySelectorAll('svg[aria-hidden="true"]').length).toBeGreaterThanOrEqual(3)
})
```

In `admin/pages/users/UsersPage.test.tsx` (or `UserPage`'s own test file if one exists):

```tsx
it('shows the flag of the user language', async () => {
  // render the user detail with language: 'ru'
  expect(screen.getByText('Ruscha').closest('div')!.querySelector('svg')).not.toBeNull()
})
```

- [ ] **Step 6: Run them and watch them fail**

Run: `cd minipolyaFrontend && npx vitest run src/miniapp/profile/ProfilePage.test.tsx src/admin/pages/users`
Expected: FAIL on the two new tests.

- [ ] **Step 7: Add `leading` to `OptionList` and wire both pickers**

`Option<T>` gains `leading?: ReactNode`, rendered inside the row before the label as `{option.leading}` (the flag is already `aria-hidden`, so no extra wrapper). `SettingsSheets.LanguageSheet` maps each language to `leading: <LanguageFlag language={value} />`. The admin `Til` row renders `<span className="inline-flex items-center gap-2"><LanguageFlag language={user.language} />{LANGUAGES[user.language] ?? user.language}</span>`.

- [ ] **Step 8: Run the full suite**

Run: `cd minipolyaFrontend && npm run typecheck && npm run lint && npm run test`
Expected: all pass.

- [ ] **Step 9: Commit**

```bash
cd minipolyaFrontend
git add src/shared/flags src/miniapp/components/OptionList.tsx src/miniapp/profile/SettingsSheets.tsx src/admin/pages/users/UserPage.tsx src/miniapp/profile/ProfilePage.test.tsx src/admin/pages/users
git commit -m "feat(i18n): inline SVG flags in the language pickers"
```

---

## Part B — Opening hours past midnight, per day of the week

### Task 2: `WeeklyHours` — the working-day rule

**Files:**
- Create: `minipolyaBackend/src/main/java/uz/minipolya/minipolya_backend/util/WeeklyHours.java`
- Test: `minipolyaBackend/src/test/java/uz/minipolya/minipolya_backend/util/WeeklyHoursTest.java`

**Interfaces:**
- Consumes: nothing (pure).
- Produces:
  ```java
  public final class WeeklyHours {
      public record Day(int dayOfWeek, boolean closed, LocalTime open, LocalTime close) {
          public int lengthHours();          // 0 when closed; 24 for 08:00→08:00-style wraps
          public boolean endsNextDay();      // close <= open, or close == 00:00
      }
      public static WeeklyHours of(List<Day> days);                    // 7 days, any order
      public static WeeklyHours sameEveryDay(LocalTime open, LocalTime close);
      public List<Day> days();                                         // always 7, Monday first
      public Day day(int dayOfWeek);
      public Day dayOf(LocalDate workingDate);
      public int weekHours();                                          // sum of lengthHours()
      public Optional<LocalDate> workingDate(ZonedDateTime slotStart); // which day opened this hour
      public static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
  }
  ```

- [ ] **Step 1: Write the failing test** — `WeeklyHoursTest`

```java
@Test void aDayClosingAfterMidnightIsTwentyHoursLong() {            // 08:00 → 04:00
    assertThat(day(0, LocalTime.of(8,0), LocalTime.of(4,0)).lengthHours()).isEqualTo(20);
}
@Test void closingAtMidnightMeansTwentyFourHundred() {               // 08:00 → 00:00
    Day d = day(0, LocalTime.of(8,0), LocalTime.MIDNIGHT);
    assertThat(d.lengthHours()).isEqualTo(16);
    assertThat(d.endsNextDay()).isTrue();
}
@Test void aSameDayWindowDoesNotEndNextDay() {
    assertThat(day(0, LocalTime.of(8,0), LocalTime.of(23,0)).endsNextDay()).isFalse();
}
@Test void theOneAmSlotOfSaturdayBelongsToSaturday() {               // Sat 2026-11-21 08:00→02:00
    WeeklyHours w = WeeklyHours.of(everyDay(LocalTime.of(8,0), LocalTime.of(2,0)));
    ZonedDateTime oneAmSunday = LocalDate.of(2026,11,22).atTime(1,0).atZone(WeeklyHours.TASHKENT);
    assertThat(w.workingDate(oneAmSunday)).contains(LocalDate.of(2026,11,21));
}
@Test void theNinePmSlotBelongsToItsOwnDate() { … contains(LocalDate.of(2026,11,21)); }
@Test void anHourOutsideEveryWindowHasNoWorkingDate() {              // 05:00, closing 02:00
    assertThat(w.workingDate(…atTime(5,0)…)).isEmpty();
}
@Test void aClosedDayOpensNothing() { … day 5 closed → workingDate of its 20:00 is empty; }
@Test void midnightConvertsThroughTheZoneNotAFixedOffset() {
    assertThat(LocalDate.of(2026,11,22).atStartOfDay(WeeklyHours.TASHKENT).toInstant())
        .isEqualTo(Instant.parse("2026-11-21T19:00:00Z"));
}
@Test void sameEveryDayGivesSevenIdenticalOpenDays() { assertThat(WeeklyHours.sameEveryDay(…).weekHours()).isEqualTo(7*14); }
@Test void ofRejectsAnythingButSevenDistinctDays() { assertThatThrownBy(() -> WeeklyHours.of(List.of(day(0,…)))) … }
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest=WeeklyHoursTest`
Expected: FAIL — `WeeklyHours` does not exist.

- [ ] **Step 3: Implement `WeeklyHours`**

`workingDate(slotStart)`: try the slot's own local date — open when `!closed && !start.isBefore(open) && (lengthHours() > hoursFrom(open, start))` with same-day arithmetic; else try the previous date when its `endsNextDay()` and the slot's clock time is before its `close` (`close == 00:00` → nothing after midnight belongs to it). Return the first match. `lengthHours()`: `close > open ? close-open : close == 00:00 ? 24-openHour : 24-openHour+closeHour`, whole hours.

- [ ] **Step 4: Run it and watch it pass**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest=WeeklyHoursTest`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd minipolyaBackend
git add src/main/java/uz/minipolya/minipolya_backend/util/WeeklyHours.java src/test/java/uz/minipolya/minipolya_backend/util/WeeklyHoursTest.java
git commit -m "feat(stadiums): WeeklyHours — the working-day rule for hours past midnight"
```

### Task 3: Schema, entity and validation

**Files:**
- Create: `minipolyaBackend/src/main/resources/db/changelog/changes/053-stadium-weekly-hours.sql`
- Modify: `minipolyaBackend/src/main/resources/db/changelog/db.changelog-master.yaml`
- Create: `.../entity/StadiumHours.java`
- Modify: `.../entity/Stadium.java` (add `hours`, `weeklyHours()`, `setWeeklyHours(...)`)
- Create: `.../util/WeeklyHoursRules.java`
- Create: `.../dto/request/WeeklyHoursRequest.java` (holds `List<DayHoursRequest> days`), `.../dto/request/DayHoursRequest.java`
- Create: `.../dto/response/DayHoursResponse.java`
- Test: `.../util/WeeklyHoursRulesTest.java`

**Interfaces:**
- Consumes: `WeeklyHours`, `WeeklyHours.Day` (Task 2).
- Produces:
  ```java
  // entity
  class StadiumHours { UUID id; Stadium stadium; int dayOfWeek; boolean closed; LocalTime openTime, closeTime; }
  // Stadium
  public WeeklyHours weeklyHours();                       // falls back to sameEveryDay(openTime, closeTime)
  public void setWeeklyHours(WeeklyHours hours);           // replaces the 7 rows AND the legacy columns
  // rules
  public static WeeklyHours WeeklyHoursRules.require(WeeklyHoursRequest r);   // 400s per the spec
  public static WeeklyHours WeeklyHoursRules.sameEveryDay(LocalTime open, LocalTime close);
  // dto
  class DayHoursRequest  { Integer dayOfWeek; Boolean closed; LocalTime openTime; LocalTime closeTime; }
  class DayHoursResponse { int dayOfWeek; boolean closed; LocalTime openTime; LocalTime closeTime; }
  ```

- [ ] **Step 1: Write the failing test** — `WeeklyHoursRulesTest`

```java
@Test void acceptsFridayEightToTwo()                    // no throw
@Test void acceptsMondayEightToMidnight()
@Test void refusesAHalfHour()                           // "openTime and closeTime must be on the hour"
@Test void refusesClosingAfterSixInTheMorning()         // 08:00→07:00 →
    // "A closing time after midnight must be 06:00 or earlier"
@Test void refusesLessThanAnHourBeforeTheNextOpening()  // Fri 08:00→02:00, Sat 02:00→06:00 →
    // "There must be at least 1 hour between Friday's closing and Saturday's opening"
@Test void refusesMoreThanTwentyFourHours()             // impossible via the ≤06:00 rule, so assert the guard
@Test void refusesEveryDayClosed()                      // "At least one day must be open"
@Test void refusesSixDays()                             // "hours must list all 7 days of the week exactly once"
@Test void refusesAnOpenDayWithoutTimes()               // "openTime and closeTime are required on an open day"
@Test void wrapsAroundTheWeek()                         // Sunday 08:00→02:00 vs Monday 02:00→06:00 refused
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest=WeeklyHoursRulesTest`
Expected: FAIL — `WeeklyHoursRules` does not exist.

- [ ] **Step 3: Write changeset 053 and register it**

```sql
--liquibase formatted sql

--changeset minipolya:053-stadium-weekly-hours
--comment: A stadium's weekly opening schedule, one row per day of the week (0 = Monday). A closing time at or before the opening time means the next morning (06:00 at the latest); 00:00 means midnight. The legacy stadiums.open_time / close_time stay and keep being written from the first open day.
CREATE TABLE stadium_hours (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    stadium_id  UUID     NOT NULL REFERENCES stadiums (id) ON DELETE CASCADE,
    day_of_week SMALLINT NOT NULL,
    closed      BOOLEAN  NOT NULL DEFAULT FALSE,
    open_time   TIME,
    close_time  TIME,
    CONSTRAINT uq_stadium_hours_day UNIQUE (stadium_id, day_of_week),
    CONSTRAINT chk_stadium_hours_day CHECK (day_of_week BETWEEN 0 AND 6),
    CONSTRAINT chk_stadium_hours_times CHECK (
        (closed AND open_time IS NULL AND close_time IS NULL)
        OR (NOT closed AND open_time IS NOT NULL AND close_time IS NOT NULL))
);
CREATE INDEX idx_stadium_hours_stadium ON stadium_hours (stadium_id);

--changeset minipolya:053-stadium-weekly-hours-backfill
--comment: Existing stadiums keep the hours they have, the same every day.
INSERT INTO stadium_hours (stadium_id, day_of_week, closed, open_time, close_time)
SELECT s.id, d.day_of_week, FALSE, s.open_time, s.close_time
FROM stadiums s CROSS JOIN generate_series(0, 6) AS d(day_of_week);
```

Add the `- include:` line to `db.changelog-master.yaml` after `052-…`.

- [ ] **Step 4: Implement the entity, the `Stadium` accessors, the DTOs and `WeeklyHoursRules`**

`StadiumHours` follows the project's entity conventions (`@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`, `@UuidGenerator(style = VERSION_7)`). `Stadium.hours` is `@OneToMany(mappedBy = "stadium", cascade = ALL, orphanRemoval = true) @OrderBy("dayOfWeek ASC")`. `Stadium.setWeeklyHours` clears and refills `hours` in place (so orphan removal fires) and sets `openTime` / `closeTime` from the first open day, keeping the NOT NULL columns valid. `WeeklyHoursRules.require` validates in the order the test asserts and returns the `WeeklyHours`; day names in messages come from `DayOfWeek.of(dayOfWeek + 1).getDisplayName(TextStyle.FULL, Locale.ENGLISH)`.

- [ ] **Step 5: Run it and watch it pass, and the schema apply**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest=WeeklyHoursRulesTest`
Expected: PASS.
Then restart the dev server and confirm Liquibase applied `053-…` and `ddl-auto: validate` accepted `StadiumHours`.

- [ ] **Step 6: Commit**

```bash
cd minipolyaBackend
git add src/main/resources/db/changelog src/main/java/uz/minipolya/minipolya_backend/entity src/main/java/uz/minipolya/minipolya_backend/util/WeeklyHoursRules.java src/main/java/uz/minipolya/minipolya_backend/dto src/test/java/uz/minipolya/minipolya_backend/util/WeeklyHoursRulesTest.java
git commit -m "feat(stadiums): stadium_hours table, entity and weekly-schedule validation"
```

### Task 4: Bookings inside the weekly window

**Files:**
- Modify: `.../service/BookingWindowValidator.java`
- Modify: `.../service/BookingSeriesService.java:78-91`
- Test: `.../service/BookingWindowValidatorTest.java` (create if absent), `.../service/BookingSeriesServiceTest.java`

**Interfaces:**
- Consumes: `Stadium.weeklyHours()` (Task 3), `WeeklyHours.workingDate` (Task 2).
- Produces: `BookingWindowValidator.validate(Stadium, OffsetDateTime)` unchanged in signature; new `public LocalDate workingDateOf(Stadium, OffsetDateTime)` throwing `BadRequestException` when the hour is not open. New message constant `public static final String NOT_OPEN = "The stadium is not open at that hour";`

- [ ] **Step 1: Write the failing test**

```java
@Test void acceptsOneAmOnAFridayClosingAtTwo()      // Fri 08:00→02:00; Sat 01:00 local accepted
@Test void acceptsElevenPmWhenClosingAtMidnight()   // Mon 08:00→00:00; Mon 23:00 accepted
@Test void refusesTwoAmWhenClosingAtTwo()           // the hour 02:00–03:00 is past closing → NOT_OPEN
@Test void refusesAnHourOnAClosedDay()              // Sunday closed → NOT_OPEN
@Test void refusesAnOffTheHourStart()               // "Booking must start on the hour"
@Test void theWorkingDateOfAOneAmSlotIsThePreviousDay()
@Test void aClosureOnTheWorkingDayCoversTheNight()  // full-day closure on Friday → 01:00 Saturday refused
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest=BookingWindowValidatorTest`
Expected: FAIL.

- [ ] **Step 3: Rewrite `validate` on top of `WeeklyHours`**

Keep the on-the-hour check; replace the open/close block with `workingDateOf(stadium, startsAt)`, and check the closure at the **working date** with the slot's clock time (so a full-day closure covers the night). Delete the `endsSameDay` logic and the "Stadium has no bookable hours" / "Booking must start at or after …" / "Booking must end by …" messages, updating every test that asserts them. In `BookingSeriesService`, replace the `fitsWindow` block with `stadium.weeklyHours().day(request.getDayOfWeek())` and the same window arithmetic: the series' `dayOfWeek` is the **working** day, and a generated occurrence's real date is the working date plus one when the hour is after midnight.

- [ ] **Step 4: Run the booking suites**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest='BookingWindowValidatorTest,BookingServiceTest,BookingSeriesServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd minipolyaBackend
git add src/main/java/uz/minipolya/minipolya_backend/service/BookingWindowValidator.java src/main/java/uz/minipolya/minipolya_backend/service/BookingSeriesService.java src/test/java/uz/minipolya/minipolya_backend/service
git commit -m "feat(bookings): validate an hour against the weekly schedule, nights included"
```

### Task 5: Availability and the free-slot count

**Files:**
- Modify: `.../service/SlotRules.java`
- Modify: `.../service/AvailabilityService.java`
- Modify: `.../dto/response/AvailabilityResponse.java` (`TimeSlot`)
- Modify: `.../service/OwnerDashboardService.java:92,200`
- Test: `.../service/SlotRulesTest.java`, `.../service/AvailabilityServiceTest.java`, `.../controller/StadiumFreeSlotsTest.java`

**Interfaces:**
- Consumes: `WeeklyHours.Day` (Task 2).
- Produces:
  ```java
  record SlotRules.Hour(LocalTime start, LocalTime end, boolean afterMidnight)
  static List<Hour> SlotRules.hoursOf(WeeklyHours.Day day)                  // empty when closed
  static OffsetDateTime SlotRules.startOf(LocalDate workingDate, Hour hour)
  static String SlotRules.status(LocalDate workingDate, Hour hour, ZonedDateTime nowLocal,
                                 List<BookedSpan> booked, Predicate<LocalTime> closedAt)
  // TimeSlot gains:
  private OffsetDateTime startsAt;   // the real UTC instant
  private Boolean afterMidnight;     // only when true (NON_NULL)
  ```

- [ ] **Step 1: Write the failing test** — `SlotRulesTest`

```java
@Test void aDayClosingAtTwoListsTheNightLast() {
    assertThat(SlotRules.hoursOf(day(LocalTime.of(22,0), LocalTime.of(2,0))))
        .containsExactly(hour(22, 23, false), hour(23, 0, false), hour(0, 1, true), hour(1, 2, true));
}
@Test void aDayClosingAtMidnightStopsAtTwentyThree() { … containsExactly(hour(22,23,false), hour(23,0,false)); }
@Test void aClosedDayHasNoHours() { assertThat(SlotRules.hoursOf(closed())).isEmpty(); }
@Test void anAfterMidnightHourStartsOnTheNextCalendarDay() {
    assertThat(SlotRules.startOf(LocalDate.of(2026,11,21), hour(1, 2, true)))
        .isEqualTo(OffsetDateTime.parse("2026-11-21T20:00:00Z"));
}
@Test void anAfterMidnightHourThatHasStartedIsPast() {   // Review Focus 3
    ZonedDateTime now = LocalDate.of(2026,11,22).atTime(0,30).atZone(TASHKENT);
    assertThat(SlotRules.status(LocalDate.of(2026,11,21), hour(0,1,true), now, List.of(), t -> false))
        .isEqualTo(TimeSlot.PAST);
}
@Test void theSameHourOfTheFollowingWorkingDayIsNotPast() { … FREE; }
```

and in `AvailabilityServiceTest`:

```java
@Test void theWorkingDaysSlotsEndOnTheNextDate()        // D 08:00→02:00 → last slot startsAt = D+1 01:00 Tashkent
@Test void anAfterMidnightSlotCarriesTheFlag()          // afterMidnight == true, startTime "01:00"
@Test void aSameDaySlotOmitsTheFlag()                   // afterMidnight == null
@Test void aBookingAfterMidnightMarksItsOwnSlotBooked()
@Test void aFullDayClosureOnTheWorkingDayClosesTheNight()
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest='SlotRulesTest,AvailabilityServiceTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`SlotRules.hoursOf(Day)` walks `lengthHours()` hours from `open`, flipping `afterMidnight` once the clock passes midnight. `status` compares `startOf(workingDate, hour)` with `nowLocal` for `past` and with the spans for `booked`, and asks `closedAt` with the hour's clock time (documented: evaluated at the working date, so a full-day closure covers the night). `AvailabilityService.getAvailability` loads bookings over `[D 00:00, D+2 00:00)`, builds the hours from `stadium.weeklyHours().dayOf(D)`, and sets `startsAt` / `afterMidnight` on each slot; `buildSlot` takes the real instant instead of recomputing it. `freeSlotCounts` loads the same two-day span and uses the same `dayOf(date)`. `OwnerDashboardService` replaces both `Duration.between(open, close)` lines with `stadium.weeklyHours().weekHours()` for the week total, and `dayOf(date).lengthHours()` per day in the schedule.

- [ ] **Step 4: Run them and watch them pass**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest='SlotRulesTest,AvailabilityServiceTest,StadiumFreeSlotsTest,OwnerDashboardServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd minipolyaBackend
git add src/main/java/uz/minipolya/minipolya_backend/service/SlotRules.java src/main/java/uz/minipolya/minipolya_backend/service/AvailabilityService.java src/main/java/uz/minipolya/minipolya_backend/dto/response/AvailabilityResponse.java src/main/java/uz/minipolya/minipolya_backend/service/OwnerDashboardService.java src/test
git commit -m "feat(availability): a working day's slots run past midnight with a real start and a flag"
```

### Task 6: Prices and promotions by the working day

**Files:**
- Modify: `.../util/PriceCalculator.java` (javadoc + param name only)
- Modify: `.../service/PromotionService.java`
- Modify: `.../service/BookingPriceCalculator.java`
- Modify: `.../service/AvailabilityService.java` (pass the working date)
- Test: `.../service/BookingPriceCalculatorTest.java`, `.../service/PromotionServiceTest.java`

**Interfaces:**
- Consumes: `WeeklyHours.workingDate` (Task 2), `SlotRules.startOf` (Task 5).
- Produces:
  ```java
  Optional<Promotion> PromotionService.getBestPromotion(UUID stadiumId, OffsetDateTime at, int workingDayOfWeek);
  // the 2-arg overload stays and delegates with the instant's own day of week
  long BookingPriceCalculator.finalHourPrice(Stadium, List<PricingRule>, LocalDate workingDate,
                                             LocalTime slotStart, OffsetDateTime slotStartUtc);
  ```

- [ ] **Step 1: Write the failing test**

```java
@Test void theMidnightSlotUsesTheWorkingDaysPriceRules() {
    // Saturday rule 00:00–02:00 = 200 000; Sunday rule 00:00–02:00 = 90 000.
    // Saturday 08:00→02:00; the slot starting Sunday 00:00 local costs 200 000.
}
@Test void theMidnightSlotUsesTheWorkingDaysPromotion() {   // Review Focus 4
    // A Sunday-only promotion does NOT discount Saturday's 00:00 slot.
}
@Test void aSameDaySlotIsUnchanged()
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest='BookingPriceCalculatorTest,PromotionServiceTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`PromotionService.findMatching` is already parameterised by `dayOfWeek`, so the new overload only changes which value is passed. `BookingPriceCalculator.hourPrice(stadium, startsAt)` resolves the working date through `stadium.weeklyHours().workingDate(...)` (falling back to the slot's own date when the hour is outside every window, so a legacy booking still prices) and passes it to `finalHourPrice`, which forwards `workingDate`'s day of week to the promotion lookup. `AvailabilityService.buildSlot` passes the working date it already has.

- [ ] **Step 4: Run them and watch them pass**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest='BookingPriceCalculatorTest,PromotionServiceTest,PricingServiceTest,GameBookingPriceParityTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd minipolyaBackend
git add src/main/java/uz/minipolya/minipolya_backend src/test/java/uz/minipolya/minipolya_backend/service
git commit -m "feat(pricing): price and discount an hour by its working day's weekday"
```

### Task 7: Owner and admin write the schedule; the API exposes it

**Files:**
- Modify: `.../dto/request/CreateStadiumRequest.java`, `UpdateStadiumRequest.java` (add `WeeklyHoursRequest hours`)
- Modify: `.../dto/response/StadiumDetailResponse.java` (add `List<DayHoursResponse> hours`)
- Modify: `.../dto/response/BookingResponse.java` (add `LocalDate workingDate`, `Boolean afterMidnight`)
- Modify: `.../util/StadiumRules.java` (hours rules move to `WeeklyHoursRules`)
- Modify: `.../service/StadiumService.java` (create/update/`auditSnapshot`), `.../mapper/StadiumMapper.java`
- Modify: `.../service/BookingResponseFactory.java`
- Create: `.../dto/request/AdminStadiumHoursRequest.java` (wraps `WeeklyHoursRequest`)
- Modify: `.../controller/admin/AdminStadiumController.java`, `.../service/AdminStadiumService.java`
- Modify: `.../audit/AuditAction.java` (add `STADIUM_HOURS_CHANGED`)
- Test: `.../controller/OwnerStadiumRulesTest.java`, `.../controller/admin/AdminStadiumHoursTest.java` (create)

**Interfaces:**
- Consumes: `WeeklyHoursRules.require` (Task 3).
- Produces: `PUT /api/admin/stadiums/{id}/hours` `{ hours: { days: [...] } }` → `AdminStadiumDetailResponse`; `hours` on the owner's create/update and on every `StadiumDetailResponse`; `workingDate` / `afterMidnight` on every `BookingResponse`.

- [ ] **Step 1: Write the failing test**

```java
// OwnerStadiumRulesTest
@Test void createWithoutHoursKeepsTheSameWindowEveryDay()        // 7 identical rows stored, hours[] in the answer
@Test void createWithAWeeklyScheduleStoresIt()                   // Fri 08:00→02:00, Sun closed
@Test void updateReplacesTheWholeSchedule()
@Test void anInvalidScheduleIs400WithTheRuleMessage()
@Test void theLegacyOpenAndCloseStillAnswerTheFirstOpenDay()
// AdminStadiumHoursTest (@DatabaseTest)
@Test void anAdminEditsTheScheduleAndItIsAudited()               // Audited.one(auditService, STADIUM_HOURS_CHANGED)
@Test void aPlayerGets403()
@Test void anUnknownStadiumIs404()
// BookingResponse
@Test void aBookingAfterMidnightReportsItsWorkingDateAndFlag()   // 00:00 Sunday → workingDate Saturday, afterMidnight true
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest='OwnerStadiumRulesTest,AdminStadiumHoursTest'`
Expected: FAIL.

- [ ] **Step 3: Implement**

`StadiumRules.hours(...)` delegates to `WeeklyHoursRules`: when a request carries `hours` it wins; when it carries only `openTime`/`closeTime` they become "same every day"; sending both with different values is 400 `"Send either hours or openTime/closeTime, not both"`. `StadiumService.create`/`update` call `stadium.setWeeklyHours(...)`; `auditSnapshot` gains one `hours` field, the schedule rendered as `"0:08:00-02:00"`-style strings in day order, so an edit diffs as one readable change. `AdminStadiumService.updateHours` follows `updateBookingTerms` exactly: `findForUpdate`, validate, audit `STADIUM_HOURS_CHANGED` with the `hours` change, `saveAndFlush`, return `get(stadiumId)`. `BookingResponseFactory` fills `workingDate` from `stadium.weeklyHours().workingDate(startsAt)` and sets `afterMidnight` only when the working date differs from the booking's own Tashkent date.

- [ ] **Step 4: Run them and watch them pass**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest='OwnerStadiumRulesTest,AdminStadiumHoursTest,StadiumServiceTest,AdminServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd minipolyaBackend
git add src/main/java src/test/java
git commit -m "feat(api): owners and admins edit the weekly schedule; bookings report their working day"
```

### Task 8: Money and the jobs around midnight (no production change expected)

**Files:**
- Create: `.../service/MidnightBookingRulesTest.java`
- Modify: `.../scheduler/ReminderJobTest.java`

**Interfaces:**
- Consumes: everything above. **Produces no production code** — if a test here fails, the fix belongs in the task that owns the code.

- [ ] **Step 1: Write the tests**

```java
@Test void theExcludeConstraintStillRejectsAnOverlapAcrossMidnight()   // 23:00 and 23:00 again → 409
@Test void aNightBookingOverlappingTheNextDaysOpeningIsRefused()
    // Fri 08:00→02:00 next to Sat 02:00→06:00 is refused at validation (Task 3); here: a Sat 02:00
    // booking does not collide with a Fri-night 01:00 booking — adjacent hours both succeed
@Test void theTwentyFourHourCancellationDeadlineIsAnInstant()           // 00:00 Sun start → deadline 00:00 Sat
@Test void cancellingAtTwentyThreeFiftyNineStillRefunds()
@Test void theThreeHourOpponentCutoffHoldsAcrossMidnight()             // a 01:00 team game closes at 22:00
@Test void theExpiryJobExpiresAnUnpaidNightBooking()
@Test void theReminderJobRemindsTwoHoursBeforeAOneAmStart()            // fires at 23:00 the previous evening
```

- [ ] **Step 2: Run them**

Run: `cd minipolyaBackend && ./mvnw -q test -Dtest='MidnightBookingRulesTest,ReminderJobTest,BookingCancellationRefundTest,BookingExpiryJobTest'`
Expected: PASS without touching production code. Any failure is a real bug — fix it in its owning class and say so in the commit.

- [ ] **Step 3: Full backend suite**

Stop the dev server, then run: `cd minipolyaBackend && ./mvnw clean test`
Expected: PASS. Restart the dev server afterwards.

- [ ] **Step 4: Commit**

```bash
cd minipolyaBackend
git add src/test/java
git commit -m "test(bookings): the instant-based rules hold across midnight"
```

### Task 9: CLAUDE.md, the API reference and the Postman collection

**Files:**
- Modify: `minipolyaBackend/CLAUDE.md` ("Stadiums", "Bookings", "Availability Calculation", "Changeset Files", "Logged actions", "Owner API", "Admin panel API")
- Modify: `minipolyaBackend/postman/MiniPolya.postman_collection.json`
- Modify: `minipolyaFrontend/docs/api-reference.md`

- [ ] **Step 1: Rewrite the hours rules in `CLAUDE.md`**

Replace "closing after midnight is not supported" everywhere with the weekly schedule and **the working-day rule**, stated once as its own short subsection under "Stadiums": a working day is the day the opening happened; slots after midnight belong to it; bookings store the real instants; pricing, promotions and closures match the working day. Add `stadium_hours` to the changeset list as `053-stadium-weekly-hours.sql`, `STADIUM_HOURS_CHANGED` to the Owner/Admin audit rows, `PUT /api/admin/stadiums/{id}/hours` to the admin table and `hours` to the owner create/update row.

- [ ] **Step 2: Add the Postman requests**

A `PUT /api/admin/stadiums/{{stadiumId}}/hours` request with a 7-day body (Friday 08:00–02:00, Sunday closed), and `hours` added to the owner create/update request bodies. Keep the collection's existing folder structure and variable names.

- [ ] **Step 3: Verify the collection still parses**

Run: `cd minipolyaBackend && node -e "JSON.parse(require('fs').readFileSync('postman/MiniPolya.postman_collection.json','utf8')); console.log('ok')"`
Expected: `ok`.

- [ ] **Step 4: Commit**

```bash
cd minipolyaBackend
git add CLAUDE.md postman
git commit -m "docs: the working-day rule, the weekly schedule endpoint and its Postman requests"
```

### Task 10: Mini App — the owner's schedule editor

**Files:**
- Create: `minipolyaFrontend/src/miniapp/owner/hours.ts`
- Create: `minipolyaFrontend/src/miniapp/owner/WeeklyHoursEditor.tsx`
- Create: `minipolyaFrontend/src/miniapp/owner/hours.test.ts`
- Modify: `.../miniapp/owner/OwnerHoursPage.tsx`, `.../owner/wizard/Steps.tsx` (`HoursStep`), `.../owner/wizard/draft.ts`
- Modify: `.../miniapp/owner/stadiumRules.ts` (drop `checkHours`'s midnight ban), `stadiumRules.test.ts`
- Modify: `.../miniapp/api/owner.ts` (types), `.../miniapp/api/stadiums.ts` (`TimeSlot`)
- Modify: `.../miniapp/i18n/locales/{uz,ru,en}.json`
- Modify: `.../miniapp/owner/stadiumSettings.test.tsx`, `.../owner/wizard/wizard.test.tsx`

**Interfaces:**
- Consumes: the backend's `hours` on `OwnerStadiumDetail` and on create/update (Task 7).
- Produces:
  ```ts
  // hours.ts
  export interface DayHours { dayOfWeek: number; closed: boolean; openTime?: string; closeTime?: string }
  export const CLOSING_HOURS: number[]          // 1..23, 0 (midnight), then 1..6 as next-morning
  export function closingLabel(hour: number, nextDay: boolean): string   // "02:00 (ertasi kuni)"
  export function sameEveryDay(open: number, close: number): DayHours[]  // 7 rows
  export function isSameEveryDay(days: DayHours[]): boolean
  export function checkWeeklyHours(days: DayHours[]): RuleKey | null
  export function hoursSummary(days: DayHours[]): string                 // "Ju–Sh 08:00–02:00, boshqa kunlar 08:00–00:00"
  // WeeklyHoursEditor.tsx
  export function WeeklyHoursEditor({ days, onChange, error }: {...}): JSX.Element
  ```

- [ ] **Step 1: Write the failing test** — `hours.test.ts`

```ts
it('summarises a uniform week', () =>
  expect(hoursSummary(sameEveryDay(8, 0))).toBe('Har kuni 08:00–00:00'))
it('groups the runs and names the rest', () =>
  expect(hoursSummary(week)).toBe('Ju–Sh 08:00–02:00, boshqa kunlar 08:00–00:00'))
it('labels a closing after midnight', () =>
  expect(closingLabel(2, true)).toBe('02:00 (ertasi kuni)'))
it('labels midnight plainly', () => expect(closingLabel(0, false)).toBe('00:00'))
it('names the closed days', () => expect(hoursSummary(withSundayClosed)).toContain('Ya yopiq'))
it('refuses a closing after 06:00 the next morning', () =>
  expect(checkWeeklyHours(sameEveryDay(8, 7))).toBe('owner.rules.closeTooLate'))
it('refuses less than an hour before the next opening', () =>
  expect(checkWeeklyHours(fridayLateSaturdayEarly)).toBe('owner.rules.hoursGap'))
it('refuses a week with every day closed', () =>
  expect(checkWeeklyHours(allClosed)).toBe('owner.rules.hoursAllClosed'))
it('accepts Friday 08:00–02:00', () => expect(checkWeeklyHours(week)).toBeNull())
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd minipolyaFrontend && npx vitest run src/miniapp/owner/hours.test.ts`
Expected: FAIL.

- [ ] **Step 3: Implement `hours.ts` and `WeeklyHoursEditor`**

`hours.ts` mirrors `WeeklyHoursRules` and nothing more (the backend still decides). `hoursSummary` groups consecutive days with identical hours, names a run as `Ju–Sh`, a single day as `Ju`, and ends with `boshqa kunlar <h>` when one shape covers the remaining days, all through `t()`. `WeeklyHoursEditor` renders a two-option segmented control — `Har kuni bir xil` (default) and `Har kun alohida` — then either one `HourStepper` pair or seven rows `Du Se Ch Pa Ju Sh Ya`, each a `Switch` labelled `Yopiq`, a `HourStepper` for opening and a `SelectField` of `CLOSING_HOURS` for closing. Switching to `Har kun alohida` seeds the seven rows from the uniform pair; switching back takes the first open day's pair. The summary line and `error` sit below.

- [ ] **Step 4: Write the failing screen tests**

In `stadiumSettings.test.tsx`, replace the "refuses a closing time not after the opening time" test with:

```tsx
it('saves Friday 08:00–02:00 and leaves the other days alone', async () => {
  // Maydonim → Ish vaqti → "Har kun alohida" → Friday's closing → 02:00 (ertasi kuni) → Saqlash
  expect(api.calls('PUT /api/owner/stadiums/s1')).toEqual([
    { hours: { days: [ /* 7 rows, Friday 08:00:00 → 02:00:00 */ ] } },
  ])
})
it('shows the summary of a mixed week', async () => {
  expect(await screen.findByText(/Ju–Sh 08:00–02:00/)).toBeInTheDocument()
})
it('no longer warns about closing after midnight', async () => {
  expect(screen.queryByText(/Yarim tundan keyin yopilish/)).toBeNull()
})
```

and in `wizard.test.tsx` the same for step "Ish vaqti va narx".

- [ ] **Step 5: Run them and watch them fail, then wire the two screens**

Run: `cd minipolyaFrontend && npx vitest run src/miniapp/owner`
Expected: FAIL, then PASS once `OwnerHoursPage` and `HoursStep` both render `WeeklyHoursEditor` and send `hours`. `draft.ts` gains `hours: DayHours[]` seeded from `sameEveryDay(8, 0)`. Delete `owner.wizard.hoursNote`'s midnight sentence and `owner.rules.hoursOrder`'s, and remove the now-dead `checkHours`.

- [ ] **Step 6: Commit**

```bash
cd minipolyaFrontend
git add src/miniapp/owner src/miniapp/api src/miniapp/i18n docs/translations-to-review.md
git commit -m "feat(owner): per-day opening hours, closing after midnight"
```

### Task 11: Mini App — players see the night slots and the real date

**Files:**
- Modify: `.../miniapp/stadiums/SlotPicker.tsx`, `.../stadiums/dates.ts`
- Modify: `.../miniapp/booking/CheckoutPage.tsx:454-455`
- Modify: `.../miniapp/bookings/when.ts`
- Modify: `.../miniapp/api/bookings.ts` (`workingDate`, `afterMidnight`)
- Modify: `.../miniapp/i18n/locales/{uz,ru,en}.json`
- Test: `.../miniapp/stadiums/*.test.tsx`, `.../miniapp/bookings/when.test.ts` (create), `.../bookings/BookingPage.test.tsx`

**Interfaces:**
- Consumes: `TimeSlot.afterMidnight` / `startsAt` (Task 5), `Booking.workingDate` / `afterMidnight` (Task 7).
- Produces: `bookingWhen(booking)` → `"Yakshanba, 16-noyabr · 00:00–01:00 (shanba kechasi)"` when `afterMidnight`.

- [ ] **Step 1: Write the failing test**

```ts
// when.test.ts
it('names the working night of an after-midnight booking', () =>
  expect(bookingWhen({ startsAt: '2026-11-15T19:00:00Z', endsAt: '2026-11-15T20:00:00Z',
                       workingDate: '2026-11-14', afterMidnight: true }))
    .toBe('Yakshanba, 16-noyabr · 00:00⁠–⁠01:00 (shanba kechasi)'))
it('leaves an ordinary booking alone', () => …)
```

```tsx
// SlotPicker test
it('labels an after-midnight slot "tun"', async () => {
  expect(screen.getByRole('radio', { name: /01:00–02:00/ })).toHaveTextContent('tun')
})
```

- [ ] **Step 2: Run them and watch them fail**

Run: `cd minipolyaFrontend && npx vitest run src/miniapp/bookings/when.test.ts src/miniapp/stadiums`
Expected: FAIL.

- [ ] **Step 3: Implement**

`SlotRow` renders a small `bg-neutral-soft` pill reading `t('slots.night')` next to the time when `slot.afterMidnight`, inside the existing label column so the row height is unchanged, and includes it in `aria-label`. `bookingWhen` appends `t('bookings.workingNight', { weekday })` when `afterMidnight`, the weekday taken from `workingDate` through `WEEKDAY_LONG_KEYS` (the same pure formatting `dayTitle` does — no rule is recomputed). `CheckoutPage`'s summary rows use the slot's own `startsAt` for the date and add the same suffix.

- [ ] **Step 4: Run them and watch them pass, then the full suite**

Run: `cd minipolyaFrontend && npm run typecheck && npm run lint && npm run test && npm run build`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
cd minipolyaFrontend
git add src/miniapp docs/translations-to-review.md
git commit -m "feat(bookings): night slots carry a tun label and show their real date"
```

### Task 12: Admin panel — show and edit the weekly schedule

**Files:**
- Modify: `minipolyaFrontend/src/admin/pages/stadiums/StadiumPage.tsx:221-223`
- Create: `minipolyaFrontend/src/admin/pages/stadiums/HoursCard.tsx`
- Modify: `minipolyaFrontend/src/admin/api/stadiums.ts`
- Test: `minipolyaFrontend/src/admin/pages/stadiums/HoursCard.test.tsx`

**Interfaces:**
- Consumes: `hours` on the admin stadium detail and `PUT /api/admin/stadiums/{id}/hours` (Task 7).
- Produces: `HoursCard({ stadium })` — the seven rows plus `Tahrirlash`, saving behind a `ConfirmModal`.

- [ ] **Step 1: Write the failing test**

```tsx
it('lists every day of the week', async () => {
  expect(screen.getByText('Juma').closest('tr')).toHaveTextContent('08:00–02:00 (ertasi kuni)')
  expect(screen.getByText('Yakshanba').closest('tr')).toHaveTextContent('Yopiq')
})
it('asks for confirmation before saving', async () => {
  // Tahrirlash → change Friday → Saqlash → the confirm modal → Tasdiqlash
  expect(api.calls('PUT /api/admin/stadiums/s1/hours')).toHaveLength(1)
})
it('sends nothing when the confirmation is dismissed', async () => {
  expect(api.calls('PUT /api/admin/stadiums/s1/hours')).toHaveLength(0)
})
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd minipolyaFrontend && npx vitest run src/admin/pages/stadiums`
Expected: FAIL.

- [ ] **Step 3: Implement `HoursCard` with the admin components**

A `PanelSection`-style card: a `DataTable` of the seven days (`Dushanba … Yakshanba`, `08:00–02:00 (ertasi kuni)` / `Yopiq`), a `Button variant="secondary"` `Tahrirlash` opening a `Modal` of seven rows (a checkbox `Yopiq`, two `FilterSelect`s of whole hours), and `Saqlash` going through `ConfirmModal` ("Ish vaqtini oʻzgartirasizmi?"). On success: toast, invalidate the stadium query. The `Ish vaqti` row in `StadiumPage` keeps the one-line summary and the card carries the detail.

- [ ] **Step 4: Run it and watch it pass, then the full suite**

Run: `cd minipolyaFrontend && npm run typecheck && npm run lint && npm run test && npm run build`
Expected: all pass.

- [ ] **Step 5: Commit**

```bash
cd minipolyaFrontend
git add src/admin
git commit -m "feat(admin): show and edit a stadium's weekly schedule"
```

### Task 13: Check it in Chrome and update the frontend CLAUDE.md

**Files:**
- Modify: `minipolyaFrontend/CLAUDE.md` (the working-day rule, under "Never re-implement business rules" / "Mini App")

- [ ] **Step 1: Run both apps and walk the flow**

With the backend up (dev profile) and `npm run dev`, in Chrome at **390×844**, **light and dark**:
set a stadium's Friday to 08:00–02:00 as the owner; as a player book the **01:00** slot of that Friday; find it in Oʻyinlarim showing the real date and `(juma kechasi)`; open it and cancel it. Capture a screenshot of each of the four screens in both themes.

- [ ] **Step 2: Fix whatever the walk-through shows, re-run the suite, commit**

- [ ] **Step 3: Record the rule in the frontend `CLAUDE.md`**

One short paragraph: a stadium's hours are a weekly schedule; a closing time at or before the opening is the next morning; after-midnight slots belong to the **working day** that opened them and arrive last in the availability list with `afterMidnight`; the client never derives that flag, and a booking's real date comes from `startsAt` while the night it belongs to comes from `workingDate`.

- [ ] **Step 4: Commit**

```bash
cd minipolyaFrontend
git add CLAUDE.md
git commit -m "docs: the working-day rule for hours past midnight"
```
