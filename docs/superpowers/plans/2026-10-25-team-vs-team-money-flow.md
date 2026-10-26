# team_vs_team Money Flow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild the team_vs_team game money flow on the existing payment foundation (`PaymentProvider`, saved cards, `platform_settings`, tiyin, `payment_status`), so each team's fee, owner advance and remainder are tracked per game *and* per team across WAITING ⇄ CONFIRMED transitions.

**Architecture:** A new `game_teams` row is the unit of money: one row per (game, side, team occupancy), carrying that team's fee, advance, remainder and remainder status, with every `payments` row pointing at it. The team-specific logic lives in a new `TeamGameService`; `GameService` keeps the shared create/join/cancel entry points and delegates when `type = team_vs_team`. A pure `TeamGameSplit` record does all the arithmetic, so the "what would I get back" preview and the real refund are computed by the same code.

**Tech Stack:** Java 21, Spring Boot 4.1.1, PostgreSQL 15+, Liquibase, MapStruct, Lombok, JUnit 5 + Mockito + AssertJ.

**Spec:** The user's task message of 2026-10-25 (steps 1–9), plus `docs/requirements-audit.md` sections F and G for the starting state. Where the audit disagrees with the code, the code wins.

## Global Constraints

- All money is `BIGINT` tiyin. Never float/double. 1 so'm = 100 tiyin.
- All ids are UUID v7 (`@UuidGenerator(style = VERSION_7)`); FKs are UUID.
- Timestamps are `TIMESTAMPTZ` stored UTC; Tashkent (`Asia/Tashkent`) only for display and for date filters.
- **New Liquibase changesets only** — never edit an applied one. Next free number: `025`.
- `ddl-auto: validate` — every entity change needs a matching changeset.
- One commit per numbered step (9 steps), plus one final docs/Postman commit. `./mvnw test` passes at every commit. Do not push.
- The `open` game type is out of scope: leave its code paths alone, do not extend them.
- Casual games keep their current flow (`POST /api/games/{id}/pay`, `handleGamePaymentConfirmed`, hosted checkout). Only the **team_vs_team branches** of those shared methods are removed.
- WAITING is persisted as `games.status = 'open'` — the existing value, kept to avoid changing casual-game behaviour. CONFIRMED is `'confirmed'`. Document the mapping; do not rename the column value.
- Refunds are always full (ATMOS cannot refund part of a transaction).
- Exact user-facing strings fixed by the spec (Uzbek, keep verbatim):
  - join after the 3-hour point → `O'yin boshlanishiga 3 soatdan kam qoldi, qo'shilib bo'lmaydi`
  - remaining team's notification → `Raqib bekor qildi, yangi raqib qidirilmoqda`
  - other notification bodies stay Russian, matching every existing `NotificationService.notify` call.
- `app.game.opponent-deadline-hours` (default `3`) is the only new config key.

## Review Focus

Input classes the spec implies but no step's own tests exercise. Each has a test assigned to the task that owns the code.

1. **`min_booking_amount >= price`** — the advance base must clamp to the price, or the remainder goes negative and the booking over-pays. → Task 1 test `splitClampsBaseToPriceWhenMinimumExceedsIt`.
2. **`min_booking_amount` is null** — advances are `P/2` each, remainder is 0, the booking is `PAID` the moment it is confirmed, and `pay-remainder` must answer 400. → Task 5 test `payRemainderWhenNothingIsOwedIs400`.
3. **`cancellation_hours < 3`** — the deadline then falls *after* the 3-hour point and the spec's three bands overlap. The 3-hour band must win (nothing back to the canceller, game over). → Task 4 test `pastThreeHourPointBeatsAStillOpenDeadline`.
4. **Two opponents joining the same WAITING game at once** — the partial unique index on `(game_id, side) WHERE status = 'active'` must reject the second, and the loser must not be charged. → Task 2 test `secondOpponentLosingTheUniqueIndexIsNotCharged`.
5. **A leader cancelling after kick-off** (`now > startsAt`) — past the 3-hour point, so it must not refund and must not leave a confirmed booking behind. → Task 4 test `cancellingAfterKickOffRefundsNothingAndEndsTheGame`.

---

### Task 1: Creating a game holds the creator's money (spec STEP 1)

**Files:**
- Create: `src/main/resources/db/changelog/changes/025-game-teams.sql`
- Create: `src/main/java/uz/minipolya/minipolya_backend/util/TeamGameSplit.java`
- Create: `src/main/java/uz/minipolya/minipolya_backend/entity/GameTeam.java`
- Create: `src/main/java/uz/minipolya/minipolya_backend/repository/GameTeamRepository.java`
- Create: `src/main/java/uz/minipolya/minipolya_backend/service/TeamGameService.java`
- Modify: `src/main/resources/db/changelog/db.changelog-master.yaml`
- Modify: `entity/Payment.java` (add `gameTeam`), `entity/Game.java` (drop `creatorSharePct`)
- Modify: `repository/PaymentRepository.java`, `service/PaymentService.java`, `service/GameService.java`
- Modify: `dto/request/CreateGameRequest.java`, `dto/response/GameDetailResponse.java`
- Modify: `config/AppProperties.java`, `src/main/resources/application.yaml`
- Modify: `controller/GameController.java` (reject `/pay` for team games)
- Test: `src/test/java/uz/minipolya/minipolya_backend/util/TeamGameSplitTest.java` (new)
- Test: `src/test/java/uz/minipolya/minipolya_backend/service/TeamGameCreateTest.java` (new)
- Test: `src/test/java/uz/minipolya/minipolya_backend/service/GameServiceTest.java` (fix constructor + drop `creatorSharePct` assertions)

**Interfaces:**
- Consumes: `PaymentProvider.hold/capture/release/refund`, `SavedCardService.requireOwnedCard(UUID userId, UUID cardId)`, `PlatformSettingsService.current()`, `BookingPriceCalculator.totalPrice(Stadium, OffsetDateTime, int)`, `BookingWindowValidator.validate(...)`.
- Produces:
  - `TeamGameSplit.of(long price, Long minBookingAmount, long fee) -> TeamGameSplit` with accessors `fee()`, `advanceFor(Side)`, `remainderFor(Side)`, `joinAmountFor(Side)`, `advanceBase()`.
  - `GameTeam` entity: `game`, `team`, `leader`, `side` (reuses `GameParticipant.Side`), `status` (`GameTeam.Status.ACTIVE|CANCELLED`), `feeAmount`, `advanceAmount`, `remainderAmount`, `remainderStatus` (`GameTeam.RemainderStatus.UNPAID|PAID`), `joinedAt`, `leftAt`.
  - `GameTeamRepository.findByGameIdAndStatus(UUID, GameTeam.Status)`, `findByGameIdAndSideAndStatus(UUID, Side, GameTeam.Status)`, `findByGameId(UUID)`.
  - `PaymentRepository.findByGameTeamId(UUID)`.
  - `PaymentService.holdForTeamGame(GameTeam gameTeam, SavedCard card) -> Payment` — provider `hold` of `fee + advance`, split `ownerAmount = advance`, `platformAmount = fee`, row written with the final status (`HELD`, or `FAILED` on a decline), `paymentType = TEAM_GAME`.
  - `TeamGameService.createWaitingGame(Game game, Team teamA, User leader, UUID cardId) -> GameTeam`.

- [ ] **Step 1: Write the failing `TeamGameSplit` tests**

`TeamGameSplitTest`, prices in tiyin (so'm × 100):

```java
@Test void splitsTheMinimumBetweenTheTwoSides() {
    // P = 200 000 so'm, M = 50 000 so'm, fee = 25 000 so'm
    TeamGameSplit s = TeamGameSplit.of(20_000_000L, 5_000_000L, 2_500_000L);
    assertThat(s.advanceFor(Side.A)).isEqualTo(2_500_000L);
    assertThat(s.advanceFor(Side.B)).isEqualTo(2_500_000L);
    assertThat(s.joinAmountFor(Side.A)).isEqualTo(5_000_000L);   // 50 000 so'm
    assertThat(s.remainderFor(Side.A)).isEqualTo(7_500_000L);    // 75 000 so'm
    assertThat(s.remainderFor(Side.B)).isEqualTo(7_500_000L);
}

@Test void theSecondSideGetsTheExtraTiyinWhenTheBaseIsOdd() {
    TeamGameSplit s = TeamGameSplit.of(1001L, 501L, 0L);
    assertThat(s.advanceFor(Side.A)).isEqualTo(250L);
    assertThat(s.advanceFor(Side.B)).isEqualTo(251L);
    assertThat(s.advanceFor(Side.A) + s.advanceFor(Side.B)).isEqualTo(501L);
    assertThat(s.remainderFor(Side.A)).isEqualTo(250L);
    assertThat(s.remainderFor(Side.B)).isEqualTo(250L);
}

@Test void withoutAStadiumMinimumEachSideAdvancesHalfThePrice() {
    TeamGameSplit s = TeamGameSplit.of(20_000_000L, null, 2_500_000L);
    assertThat(s.advanceFor(Side.A)).isEqualTo(10_000_000L);
    assertThat(s.remainderFor(Side.A)).isZero();
    assertThat(s.remainderFor(Side.B)).isZero();
}

@Test void splitClampsBaseToPriceWhenMinimumExceedsIt() {   // Review Focus 1
    TeamGameSplit s = TeamGameSplit.of(10_000_000L, 90_000_000L, 0L);
    assertThat(s.advanceBase()).isEqualTo(10_000_000L);
    assertThat(s.remainderFor(Side.A)).isZero();
    assertThat(s.remainderFor(Side.B)).isZero();
}
```

- [ ] **Step 2: Run them and confirm they fail**

Run: `./mvnw test -Dtest=TeamGameSplitTest`
Expected: FAIL — `TeamGameSplit` does not exist.

- [ ] **Step 3: Write `TeamGameSplit`**

A `record TeamGameSplit(long fee, long advanceBase, long advanceA, long advanceB, long remainderA, long remainderB)` in `util/`. `of(price, minBookingAmount, fee)` computes `advanceBase = (min == null || min > price) ? price : min`, floors the A half of both `advanceBase` and `price - advanceBase`, and gives B the remainder of each. Pure, no Spring annotation.

- [ ] **Step 4: Run them and confirm they pass**

Run: `./mvnw test -Dtest=TeamGameSplitTest` → PASS

- [ ] **Step 5: Write changeset `025-game-teams.sql` and register it**

Three changesets in one file, ids `025-create-game-teams`, `025-payment-game-team`, `025-drop-creator-share-pct`:

```sql
CREATE TABLE game_teams (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    game_id          UUID NOT NULL REFERENCES games (id) ON DELETE CASCADE,
    team_id          UUID NOT NULL REFERENCES teams (id),
    leader_id        UUID NOT NULL REFERENCES users (id),
    side             VARCHAR(1) NOT NULL CHECK (side IN ('a', 'b')),
    status           VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'cancelled')),
    fee_amount       BIGINT NOT NULL CHECK (fee_amount >= 0),
    advance_amount   BIGINT NOT NULL CHECK (advance_amount >= 0),
    remainder_amount BIGINT NOT NULL CHECK (remainder_amount >= 0),
    remainder_status VARCHAR(20) NOT NULL DEFAULT 'unpaid' CHECK (remainder_status IN ('unpaid', 'paid')),
    joined_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    left_at          TIMESTAMPTZ
);
-- One live occupant per side; past occupants stay for their payment history.
CREATE UNIQUE INDEX uq_game_teams_active_side ON game_teams (game_id, side) WHERE status = 'active';
CREATE INDEX idx_game_teams_game_id ON game_teams (game_id);
CREATE INDEX idx_game_teams_team_id ON game_teams (team_id);

ALTER TABLE payments ADD COLUMN game_team_id UUID REFERENCES game_teams (id);
CREATE INDEX idx_payments_game_team_id ON payments (game_team_id);

ALTER TABLE games DROP COLUMN creator_share_pct;
```

Add the `include` entry to `db.changelog-master.yaml`.

- [ ] **Step 6: Write the entity, repository and `Payment.gameTeam`**

`GameTeam` follows the house style (`@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor`, `@UuidGenerator(VERSION_7)`, lowercase `AttributeConverter`s for `Side`/`Status`/`RemainderStatus`, `@PrePersist` filling `joinedAt`). Reuse `GameParticipant.Side` rather than declaring a second side enum. Add `@ManyToOne(fetch = LAZY) @JoinColumn(name = "game_team_id") private GameTeam gameTeam;` to `Payment`, and `findByGameTeamId` to `PaymentRepository`.

- [ ] **Step 7: Write the failing create test**

`TeamGameCreateTest` (plain Mockito, no Spring context — copy the wiring style of `BookingPaymentTest`):

```java
@Test void creatingATeamGameHoldsFeePlusAdvanceAndLeavesTheSlotFree() {
    // P = 200 000 so'm, M = 50 000 so'm, fee = 25 000 so'm
    service.createGame(request(CARD_ID), LEADER_A_ID);
    verify(paymentProvider).hold(chargeCaptor.capture());
    assertThat(chargeCaptor.getValue().amount()).isEqualTo(5_000_000L);
    assertThat(chargeCaptor.getValue().ownerAmount()).isEqualTo(2_500_000L);   // advance
    assertThat(chargeCaptor.getValue().platformAmount()).isEqualTo(2_500_000L); // fee
    verify(bookingRepository, never()).saveAndFlush(any());
    assertThat(savedGame().getStatus()).isEqualTo("open");
}

@Test void aDeclinedCardMeansNoGame() {
    when(paymentProvider.hold(any())).thenReturn(new ProviderTransaction("failed-1", FAILED));
    assertThatThrownBy(() -> service.createGame(request(CARD_ID), LEADER_A_ID))
            .isInstanceOf(BadRequestException.class);
    verify(gameRepository, never()).save(argThat(g -> "confirmed".equals(g.getStatus())));
}

@Test void theHeldPaymentIsRecordedAgainstTheGameTeam() {
    service.createGame(request(CARD_ID), LEADER_A_ID);
    Payment p = savedPayment();
    assertThat(p.getStatus()).isEqualTo(Payment.Status.HELD);
    assertThat(p.getPaymentType()).isEqualTo(Payment.PaymentType.TEAM_GAME);
    assertThat(p.getGameTeam().getSide()).isEqualTo(Side.A);
}

@Test void aTeamGameNeedsACardId() {
    assertThatThrownBy(() -> service.createGame(request(null), LEADER_A_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("cardId");
}
```

- [ ] **Step 8: Run it and confirm it fails**

Run: `./mvnw test -Dtest=TeamGameCreateTest` → FAIL (no `TeamGameService`, no `cardId` on the request).

- [ ] **Step 9: Implement creation**

- `CreateGameRequest`: add `private UUID cardId;` documented as required for `team_vs_team`.
- `AppProperties.Game`: replace `defaultCreatorSharePct` with `private int opponentDeadlineHours = 3;`; add `app.game.opponent-deadline-hours: 3` to `application.yaml`.
- `PaymentService.holdForTeamGame(GameTeam, SavedCard)` as in Interfaces. No booking commission is applied to team games — the platform's cut *is* the fee.
- `TeamGameService.createWaitingGame(Game, Team, User, UUID cardId)`: reject a null `cardId`; build the split from `game.getTotalPrice()`, `stadium.getMinBookingAmount()` and `settings.getTeamGamePlatformFee()`; save the side-A `GameTeam` with its fee/advance/remainder; hold; throw `BadRequestException("Card payment was declined")` when the hold fails (the transaction rolls back, so no game survives).
- `GameService.createGame`: keep the existing validation and `Game` build, drop `.creatorSharePct(...)`, and for `team_vs_team` call `teamGameService.createWaitingGame(...)` after saving the game, before the audit log.
- Remove `creatorSharePct` from `Game`, `GameDetailResponse`, and the `team_vs_team` branch of `PaymentService.gameShareAmount` (leave the casual `total / players + fee` branch).
- `GameService.payForGame`: throw `BadRequestException("Team games are paid when creating or joining, not here")` when `type = team_vs_team`.
- Fix `GameServiceTest` for the new `GameService` constructor argument and the removed field.

- [ ] **Step 10: Run the full suite**

Run: `./mvnw test`
Expected: BUILD SUCCESS.

- [ ] **Step 11: Commit**

```bash
git add -A -- src docs/superpowers
git commit -m "feat(games): a team game holds its creator's fee and advance

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Joining a WAITING game (spec STEP 2)

**Files:**
- Modify: `service/TeamGameService.java`, `service/GameService.java`, `service/PaymentService.java`
- Modify: `dto/request/JoinGameRequest.java`
- Test: `src/test/java/uz/minipolya/minipolya_backend/service/TeamGameJoinTest.java` (new)

**Interfaces:**
- Consumes: `GameTeamRepository`, `TeamGameSplit`, `PaymentService.holdForTeamGame` (Task 1).
- Produces:
  - `TeamGameService.join(Game game, UUID userId, UUID teamId, UUID cardId) -> GameTeam`
  - `TeamGameService.opponentDeadline(Game game) -> OffsetDateTime` = `startsAt.minusHours(appProperties.getGame().getOpponentDeadlineHours())`
  - `PaymentService.chargeForTeamGame(GameTeam gameTeam, SavedCard card, Payment.PaymentType type) -> Payment` — provider `charge`, same split rule as the hold, status `PAID` or `FAILED`.
  - `PaymentService.captureHeld(Payment held) -> Payment` — provider `capture`, row moves `HELD → CAPTURED`.

- [ ] **Step 1: Write the failing join tests**

`TeamGameJoinTest`:

```java
@Test void joiningBooksTheSlotChargesTheJoinerAndCapturesTheHold() {
    service.join(waitingGame(), LEADER_B_ID, TEAM_B_ID, CARD_B_ID);
    InOrder order = inOrder(bookingRepository, paymentProvider);
    order.verify(bookingRepository).saveAndFlush(any(Booking.class));
    order.verify(paymentProvider).charge(any());
    order.verify(paymentProvider).capture("txn-hold-a");
    assertThat(savedGame().getStatus()).isEqualTo("confirmed");
    assertThat(savedBooking().getStatus()).isEqualTo("confirmed");
    assertThat(savedBooking().getSource()).isEqualTo("game");
}

@Test void theBookingIsPartiallyPaidByBothAdvances() {
    service.join(waitingGame(), LEADER_B_ID, TEAM_B_ID, CARD_B_ID);
    assertThat(savedBooking().getPaidAmount()).isEqualTo(5_000_000L);   // M = 50 000 so'm
    assertThat(savedBooking().getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
}

@Test void joiningAfterTheThreeHourPointIs400() {
    assertThatThrownBy(() -> service.join(gameStartingIn(Duration.ofHours(2)), LEADER_B_ID, TEAM_B_ID, CARD_B_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("O'yin boshlanishiga 3 soatdan kam qoldi, qo'shilib bo'lmaydi");
    verify(paymentProvider, never()).charge(any());
}

@Test void aTakenSlotCancelsTheGameRefundsTheWaitingTeamAndChargesNobody() {
    when(bookingRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("overlap"));
    assertThatThrownBy(() -> service.join(waitingGame(), LEADER_B_ID, TEAM_B_ID, CARD_B_ID))
            .isInstanceOf(SlotAlreadyBookedException.class);
    assertThat(savedGame().getStatus()).isEqualTo("cancelled_slot_taken");
    verify(paymentProvider).release("txn-hold-a");
    verify(paymentProvider, never()).charge(any());
}

@Test void aDeclinedJoinerLeavesTheGameWaitingAndRemovesThePendingBooking() {
    when(paymentProvider.charge(any())).thenReturn(new ProviderTransaction("failed-1", FAILED));
    assertThatThrownBy(() -> service.join(waitingGame(), LEADER_B_ID, TEAM_B_ID, CARD_B_ID))
            .isInstanceOf(BadRequestException.class);
    verify(bookingRepository).delete(any(Booking.class));
    verify(paymentProvider, never()).capture(any());
    assertThat(game.getStatus()).isEqualTo("open");
}

@Test void aFailedCaptureRefundsTheJoinerAndCancelsEverything() {
    when(paymentProvider.capture("txn-hold-a")).thenThrow(new BadRequestException("gone"));
    assertThatThrownBy(() -> service.join(waitingGame(), LEADER_B_ID, TEAM_B_ID, CARD_B_ID))
            .isInstanceOf(BadRequestException.class);
    verify(paymentProvider).refund("txn-charge-b");
    verify(bookingRepository).delete(any(Booking.class));
    assertThat(savedGame().getStatus()).isEqualTo("cancelled_slot_taken");
}

@Test void secondOpponentLosingTheUniqueIndexIsNotCharged() {   // Review Focus 4
    when(gameTeamRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_game_teams_active_side"));
    assertThatThrownBy(() -> service.join(waitingGame(), LEADER_B_ID, TEAM_B_ID, CARD_B_ID))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("opponent");
    verify(paymentProvider, never()).charge(any());
}
```

The existing side-B validations (`teamId` required, caller leads it, not the waiting team) already live in `GameService.acceptAsTeamB` and stay covered by `GameServiceTest`.

- [ ] **Step 2: Run and confirm failure**

Run: `./mvnw test -Dtest=TeamGameJoinTest` → FAIL.

- [ ] **Step 3: Implement joining**

- `JoinGameRequest`: add `private UUID cardId;`.
- `TeamGameService.join`, in this order, inside one `@Transactional`:
  1. Validate side B as today (move `acceptAsTeamB`'s checks in, or call it), then reject `now.isAfter(opponentDeadline(game))` with the exact Uzbek message, then `saveAndFlush` the side-B `GameTeam` so the partial unique index fires here (translate `DataIntegrityViolationException` to `BadRequestException("This game already has an opponent")`).
  2. `bookingRepository.saveAndFlush` a `source = "game"`, `status = "pending"` booking for `game.startsAt → game.endsAt`, `price = game.totalPrice`, `gameId = game.getId()`, `user = game.getCreator()`. On `DataIntegrityViolationException`: cancel the game `cancelled_slot_taken`, give the waiting team everything back, notify, and throw `SlotAlreadyBookedException`.
  3. `paymentService.chargeForTeamGame(teamB, card, TEAM_GAME)`. On a failed row: delete the pending booking, mark the side-B `GameTeam` cancelled, and throw `BadRequestException("Card payment was declined; the game is still looking for an opponent")`.
  4. Capture side A's `HELD` payment. On any exception: refund the joiner in full, delete the booking, cancel the game `cancelled_slot_taken`, `log.error`, rethrow.
  5. Set `booking.paidAmount` = sum of the active `GameTeam` advances, `paymentStatus` = `PAID` when that reaches `price` else `PARTIALLY_PAID`, `status = "confirmed"`, `expiresAt = null`; set `game.bookingId`, `game.status = "confirmed"`, `game.teamB`; notify both leaders and the owner; audit `GAME_CONFIRMED`.
- `GameService.joinGame`: for `team_vs_team` with `side = "b"`, delegate to `teamGameService.join(...)`; members joining with no side keep the current no-payment path.

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw test -Dtest=TeamGameJoinTest` → PASS, then `./mvnw test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A -- src
git commit -m "feat(games): an opponent joining books the slot and settles both teams

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: How a WAITING game ends without an opponent (spec STEP 3)

**Files:**
- Modify: `service/TeamGameService.java`, `service/GameService.java`, `service/PaymentService.java`
- Modify: `scheduler/GameTimeoutJob.java` (javadoc only — the offset lives in the service)
- Modify: `repository/GameRepository.java`
- Test: `src/test/java/uz/minipolya/minipolya_backend/service/TeamGameWaitingEndTest.java` (new)
- Test: `service/GameServiceTest.java` (existing `cancelUnfilledGames` expectations)

**Interfaces:**
- Produces:
  - `PaymentService.giveBackInFull(Payment original, String reason) -> Payment` — releases a `HELD` row (provider `release`, status → `RELEASED`, **no** new row, since no money ever moved) and otherwise delegates to `refundInFull`. Returns null when there was nothing to give back.
  - `PaymentService.giveBackAllTeamPayments(UUID gameTeamId, String reason) -> long` — tiyin actually returned.
  - `PaymentService.keepAllTeamPayments(UUID gameTeamId) -> long` — captures every `HELD` row, leaves `PAID`/`CAPTURED` alone, returns tiyin kept.
  - `PaymentService.refundableTeamTotal(UUID gameTeamId) -> long` — what `giveBackAllTeamPayments` would return, without moving money.
  - `TeamGameService.cancelByLeader(Game game, UUID userId)`
  - `TeamGameService.endWaitingGame(Game game, String cancelStatus, String reason)` — cancels, gives every active team everything back, notifies.
  - `GameRepository.findByTypeAndStatusAndStartsAtBefore(String type, String status, OffsetDateTime time)`

- [ ] **Step 1: Write the failing tests**

`TeamGameWaitingEndTest`:

```java
@Test void aDirectBookingOnTheSlotCancelsTheGameAndReleasesTheHold() {
    gameService.cancelGamesOnSlot(STADIUM_ID, game.getStartsAt(), game.getEndsAt());
    assertThat(savedGame().getStatus()).isEqualTo("cancelled_slot_taken");
    verify(paymentProvider).release("txn-hold-a");
}

@Test void theJobCancelsAWaitingTeamGameThreeHoursBeforeTheStart() {
    // starts in 2h 30m, still waiting
    assertThat(gameService.cancelUnfilledGames()).isEqualTo(1);
    assertThat(savedGame().getStatus()).isEqualTo("cancelled_not_filled");
    verify(paymentProvider).release("txn-hold-a");
}

@Test void theJobLeavesAWaitingTeamGameAloneFourHoursBeforeTheStart() {
    assertThat(gameService.cancelUnfilledGames()).isZero();
    verify(paymentProvider, never()).release(any());
}

@Test void casualGamesStillTimeOutAtTheStartTimeNotThreeHoursBefore() {
    // one casual game starting in 1h, one already started
    assertThat(gameService.cancelUnfilledGames()).isEqualTo(1);
}

@Test void theWaitingLeaderCancellingBeforeTheDeadlineGetsEverythingBack() {
    service.cancelByLeader(gameStartingIn(Duration.ofDays(2)), LEADER_A_ID);
    verify(paymentProvider).release("txn-hold-a");
    assertThat(savedGame().getStatus()).isEqualTo("cancelled_by_creator");
}

@Test void theWaitingLeaderCancellingAfterTheDeadlineGetsNothingBack() {
    // cancellationHours = 24, starts in 10h, so past the deadline but before the 3-hour point
    service.cancelByLeader(gameStartingIn(Duration.ofHours(10)), LEADER_A_ID);
    verify(paymentProvider).capture("txn-hold-a");
    verify(paymentProvider, never()).release(any());
}

@Test void theWaitingLeaderCancellingPastTheThreeHourPointGetsEverythingBack() {
    // the auto-cancel would have refunded them anyway; beating the job must not cost them
    service.cancelByLeader(gameStartingIn(Duration.ofHours(2)), LEADER_A_ID);
    verify(paymentProvider).release("txn-hold-a");
    assertThat(savedGame().getStatus()).isEqualTo("cancelled_not_filled");
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./mvnw test -Dtest=TeamGameWaitingEndTest` → FAIL.

- [ ] **Step 3: Implement the three endings**

- The three `PaymentService` methods above. `giveBackInFull` must treat `HELD` as "money back" and leave `FAILED`/`RELEASED`/`REFUNDED`/cash alone.
- `GameService.cancelGamesOnSlot`: when the cancelled game is `team_vs_team`, call `teamGameService.endWaitingGame(game, "cancelled_slot_taken", ...)` instead of `refundPaidParticipants`.
- `GameService.cancelUnfilledGames`: two queries — `team_vs_team` games still `open` with `startsAt < now + opponentDeadlineHours`, and every other type still `open` with `startsAt < now` (today's behaviour). Team games route through `endWaitingGame(game, "cancelled_not_filled", ...)`.
- `TeamGameService.cancelByLeader` for a WAITING game: allow the leader of **any** active `GameTeam` (there is only one while waiting); if `now > opponentDeadline` → `endWaitingGame(..., "cancelled_not_filled", ...)` (everything back); else if `now <= stadium deadline` → cancel `cancelled_by_creator` with everything back; else cancel `cancelled_by_creator` and `keepAllTeamPayments`.

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw test -Dtest=TeamGameWaitingEndTest` → PASS, then `./mvnw test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A -- src
git commit -m "feat(games): a waiting team game ends on a taken slot, the 3-hour point or a cancel

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Cancelling a CONFIRMED game (spec STEP 4)

**Files:**
- Create: `src/main/resources/db/changelog/changes/026-opponent-left.sql`
- Modify: `db.changelog-master.yaml`, `entity/Notification.java`
- Modify: `service/TeamGameService.java`, `service/GameService.java`, `service/BookingService.java`, `service/AdminService.java`
- Test: `src/test/java/uz/minipolya/minipolya_backend/service/TeamGameConfirmedCancelTest.java` (new)
- Test: `service/BookingCancellationRefundTest.java`, `service/AdminServiceTest.java` (new cases)

**Interfaces:**
- Produces:
  - `TeamGameService.cancelByLeader(Game, UUID)` extended to CONFIRMED games (same signature).
  - `TeamGameService.cancelForBookingCancelled(UUID gameId, String reason)` — the owner/admin path: both teams get everything back and the game ends.
  - `GameService.cancelGameForBooking(UUID gameId, String reason)` — the entry point `BookingService.cancelByOwner` and `AdminService.cancelFutureBookings` call when `booking.source = "game"`.

**Game status values.** The `games.status` CHECK has no owner-cancellation value and the spec does not ask for one, so: a leader's cancellation writes `cancelled_by_creator`, an owner's or admin's writes `cancelled_slot_taken`, and the two are told apart by the audit entry — `GAME_CANCELLED` with `{"reason":"booking_cancelled_by_owner"}`.

- [ ] **Step 1: Write changeset `026-opponent-left.sql` and register it**

```sql
--changeset minipolya:026-booking-opponent-left
ALTER TABLE bookings DROP CONSTRAINT bookings_status_check;
ALTER TABLE bookings ADD CONSTRAINT bookings_status_check
    CHECK (status IN ('pending', 'confirmed', 'completed', 'expired',
                      'cancelled_by_user', 'cancelled_by_owner', 'cancelled_opponent_left'));

--changeset minipolya:026-notification-opponent-left
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check
    CHECK (type IN (<the 14 existing values>, 'game_opponent_left'));
```

The EXCLUDE constraint is `WHERE status IN ('pending','confirmed')`, so the new status frees the slot with no further change. `OwnerDashboardService.OCCUPIED_STATUSES`/`COUNTED_STATUSES` exclude it already. Add `GAME_OPPONENT_LEFT` to `Notification.Type`.

- [ ] **Step 2: Write the failing tests**

`TeamGameConfirmedCancelTest` (`cancellationHours = 24`, `opponentDeadlineHours = 3`):

```java
@Test void cancellingBeforeTheDeadlineRefundsTheCancellerAndReopensTheGame() {
    service.cancelByLeader(confirmedGameStartingIn(Duration.ofDays(2)), LEADER_B_ID);
    verify(paymentProvider).refund("txn-charge-b");
    verify(paymentProvider, never()).refund("txn-charge-a");
    assertThat(savedBooking().getStatus()).isEqualTo("cancelled_opponent_left");
    assertThat(savedGame().getStatus()).isEqualTo("open");
    assertThat(savedGame().getTeamB()).isNull();
    assertThat(activeTeams()).extracting(GameTeam::getSide).containsExactly(Side.A);
}

@Test void theRemainingTeamKeepsItsPaymentsWhenTheOpponentLeaves() {
    service.cancelByLeader(confirmedGameStartingIn(Duration.ofDays(2)), LEADER_B_ID);
    assertThat(teamA().getStatus()).isEqualTo(GameTeam.Status.ACTIVE);
    assertThat(paymentOf(teamA()).getStatus()).isEqualTo(Payment.Status.CAPTURED);
}

@Test void aRemainderPaidInTheAppComesBackWithAnEarlyCancellation() {
    service.cancelByLeader(confirmedGameWithPaidRemainder(Duration.ofDays(2)), LEADER_B_ID);
    verify(paymentProvider).refund("txn-charge-b");
    verify(paymentProvider).refund("txn-remainder-b");
}

@Test void cancellingAfterTheDeadlineRefundsNothingButStillReopensTheGame() {
    service.cancelByLeader(confirmedGameStartingIn(Duration.ofHours(10)), LEADER_B_ID);
    verify(paymentProvider, never()).refund(any());
    assertThat(savedGame().getStatus()).isEqualTo("open");
}

@Test void cancellingPastTheThreeHourPointEndsTheGameAndRefundsTheRemainingTeam() {
    service.cancelByLeader(confirmedGameStartingIn(Duration.ofHours(2)), LEADER_B_ID);
    verify(paymentProvider, never()).refund("txn-charge-b");
    verify(paymentProvider).refund("txn-charge-a");
    assertThat(savedGame().getStatus()).isEqualTo("cancelled_not_filled");
    assertThat(savedBooking().getStatus()).isEqualTo("cancelled_opponent_left");
}

@Test void pastThreeHourPointBeatsAStillOpenDeadline() {   // Review Focus 3
    stadium.setCancellationHours(1);           // deadline is T-1h, after the 3-hour point
    service.cancelByLeader(confirmedGameStartingIn(Duration.ofHours(2)), LEADER_B_ID);
    verify(paymentProvider, never()).refund("txn-charge-b");
    assertThat(savedGame().getStatus()).isEqualTo("cancelled_not_filled");
}

@Test void cancellingAfterKickOffRefundsNothingAndEndsTheGame() {   // Review Focus 5
    service.cancelByLeader(confirmedGameStartingIn(Duration.ofHours(-1)), LEADER_B_ID);
    verify(paymentProvider, never()).refund("txn-charge-b");
    assertThat(savedGame().getStatus()).isEqualTo("cancelled_not_filled");
}

@Test void eitherLeaderCanCancel() {
    service.cancelByLeader(confirmedGameStartingIn(Duration.ofDays(2)), LEADER_A_ID);
    assertThat(activeTeams()).extracting(GameTeam::getSide).containsExactly(Side.B);
}

@Test void aNonLeaderCannotCancel() {
    assertThatThrownBy(() -> service.cancelByLeader(confirmedGame(), SOMEONE_ELSE_ID))
            .isInstanceOf(ForbiddenException.class);
}

@Test void theOwnerCancellingTheBookingRefundsBothTeams() {
    bookingService.cancelByOwner(OWNER_ID, BOOKING_ID, "Pitch flooded");
    verify(paymentProvider).refund("txn-charge-a");
    verify(paymentProvider).refund("txn-charge-b");
    assertThat(savedGame().getStatus()).isEqualTo("cancelled_slot_taken");
}
```

In `AdminServiceTest`: blocking the stadium cancels a WAITING team game with everything back and refunds both teams of a confirmed one.

- [ ] **Step 3: Run and confirm failure**

Run: `./mvnw test -Dtest=TeamGameConfirmedCancelTest` → FAIL.

- [ ] **Step 4: Implement**

`TeamGameService.cancelByLeader` on a CONFIRMED game resolves the canceller's active `GameTeam` (403 otherwise), then:

1. Cancel the booking: `status = "cancelled_opponent_left"`, `cancelledAt`, `cancellationReason`, save — the slot is free again.
2. `now > opponentDeadline(game)` → canceller keeps nothing (`keepAllTeamPayments`), the remaining team gets everything back (`giveBackAllTeamPayments`), both `GameTeam`s go `CANCELLED`, game → `cancelled_not_filled`.
3. Otherwise → canceller gets everything back if `now <= cancellationPolicy deadline`, else `keepAllTeamPayments`; the canceller's `GameTeam` goes `CANCELLED` with `leftAt`; the remaining team is untouched; `game.status = "open"`, `game.bookingId = null`, and `teamA`/`teamB` is cleared on the leaving side (if side A left, promote nothing — leave side A empty and keep team B on side B).
4. Notify: remaining leader `GAME_OPPONENT_LEFT` / `"Raqib bekor qildi, yangi raqib qidirilmoqda"`; canceller `GAME_CANCELLED` with the tiyin returned; stadium owner `BOOKING_CANCELLED` saying the slot is free again. Audit `GAME_OPPONENT_LEFT` or `GAME_CANCELLED`.

`GameService.cancelGame` routes `team_vs_team` to `cancelByLeader` for both WAITING and CONFIRMED (today it refuses anything but `open` and only allows the creator).

`BookingService.cancelByOwner` and `AdminService.cancelFutureBookings`: when `booking.getSource()` is `"game"` and `booking.getGameId() != null`, call `gameService.cancelGameForBooking(gameId, reason)` instead of `refundAllCardPayments` — the game owns the money, and it refunds both teams, cancels the game and marks every `GameTeam` `CANCELLED` and every participant `REFUNDED`.

- [ ] **Step 5: Run and confirm it passes**

Run: `./mvnw test` → BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add -A -- src
git commit -m "feat(games): cancelling a confirmed team game reopens or ends it by deadline band

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Remainder per team (spec STEP 5)

**Files:**
- Create: `dto/request/PayGameRemainderRequest.java`, `dto/request/GameCashRequest.java`, `dto/response/GameTeamResponse.java`
- Modify: `service/TeamGameService.java`, `service/PaymentService.java`, `controller/GameController.java`
- Modify: `dto/response/GameDetailResponse.java`, `service/GameService.java` (fill `teams` on the detail)
- Test: `src/test/java/uz/minipolya/minipolya_backend/service/TeamGameRemainderTest.java` (new)

**Interfaces:**
- Produces:
  - `POST /api/games/{id}/pay-remainder` body `{ "cardId": "<uuid>" }` → `GameDetailResponse`
  - `POST /api/games/{id}/cash` body `{ "side": "a" }` → `GameDetailResponse` (stadium owner only)
  - `TeamGameService.payRemainder(UUID gameId, UUID userId, UUID cardId) -> GameDetailResponse`
  - `TeamGameService.recordCashRemainder(UUID gameId, UUID ownerUserId, Side side) -> GameDetailResponse`
  - `GameTeamResponse { Side side; UUID teamId; String teamName; Long feeAmount; Long advanceAmount; Long paidInApp; Long remainderAmount; String remainderStatus; }`
  - `GameDetailResponse.teams : List<GameTeamResponse>`

- [ ] **Step 1: Write the failing tests**

```java
@Test void aLeaderPaysTheirOwnTeamsRemainderFromASavedCard() {
    service.payRemainder(GAME_ID, LEADER_B_ID, CARD_B_ID);
    verify(paymentProvider).charge(argThat(c -> c.amount() == 7_500_000L));   // 75 000 so'm
    assertThat(teamB().getRemainderStatus()).isEqualTo(RemainderStatus.PAID);
    assertThat(savedBooking().getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
}

@Test void theBookingIsPaidOnlyWhenBothActiveTeamsHaveSettled() {
    teamA().setRemainderStatus(RemainderStatus.PAID);
    service.payRemainder(GAME_ID, LEADER_B_ID, CARD_B_ID);
    assertThat(savedBooking().getPaidAmount()).isEqualTo(PRICE);
    assertThat(savedBooking().getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
}

@Test void payingATeamsRemainderTwiceIs400() {
    teamB().setRemainderStatus(RemainderStatus.PAID);
    assertThatThrownBy(() -> service.payRemainder(GAME_ID, LEADER_B_ID, CARD_B_ID))
            .isInstanceOf(BadRequestException.class).hasMessage("Already paid");
}

@Test void theOwnerMarksOneTeamsRemainderAsCash() {
    service.recordCashRemainder(GAME_ID, OWNER_ID, Side.A);
    assertThat(teamA().getRemainderStatus()).isEqualTo(RemainderStatus.PAID);
    assertThat(teamB().getRemainderStatus()).isEqualTo(RemainderStatus.UNPAID);
    assertThat(savedPayment().getMethod()).isEqualTo(Payment.Method.CASH);
}

@Test void cashAfterTheCardLosesWith400() {
    teamA().setRemainderStatus(RemainderStatus.PAID);
    assertThatThrownBy(() -> service.recordCashRemainder(GAME_ID, OWNER_ID, Side.A))
            .isInstanceOf(BadRequestException.class).hasMessage("Already paid");
}

@Test void payRemainderWhenNothingIsOwedIs400() {   // Review Focus 2
    // stadium has no minimum: both advances already covered the price
    assertThatThrownBy(() -> service.payRemainder(noMinimumGame(), LEADER_B_ID, CARD_B_ID))
            .isInstanceOf(BadRequestException.class).hasMessage("Already paid");
}

@Test void aLeaderCannotPayTheOtherTeamsRemainder() {
    assertThatThrownBy(() -> service.payRemainder(GAME_ID, LEADER_A_ID, CARD_B_ID))
            .isInstanceOf(ResourceNotFoundException.class);   // the card is not theirs
}

@Test void theGameDetailShowsEachTeamsMoney() {
    GameDetailResponse detail = gameService.getGameDetail(GAME_ID, LEADER_A_ID);
    assertThat(detail.getTeams()).hasSize(2);
    assertThat(detail.getTeams().getFirst().getRemainderStatus()).isEqualTo("unpaid");
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./mvnw test -Dtest=TeamGameRemainderTest` → FAIL.

- [ ] **Step 3: Implement**

- `payRemainder`: find the caller's **active** `GameTeam` on the game (403 if none); 400 `"Already paid"` when `remainderStatus = PAID` or `remainderAmount == 0`; charge the card for `remainderAmount` as `Payment.PaymentType.REMAINDER` with `ownerAmount = amount, platformAmount = 0` (the platform's cut on a team game is the fee, taken at join); on a decline leave `UNPAID` and return 400. On success mark `PAID`, recompute the booking, audit.
- `recordCashRemainder`: owner of `game.stadium` only; same "Already paid" rule; writes a `CASH`/`CASH` provider row of `remainderAmount`.
- One private `refreshBookingPayment(Game game)` recomputes `booking.paidAmount` as the sum over active `GameTeam`s of `advanceAmount + (remainderStatus == PAID ? remainderAmount : 0)` and sets `paymentStatus` from it. Everything that changes team money calls it (Tasks 2, 4 and 5).
- Fill `GameDetailResponse.teams` in `GameService.toDetail` for `team_vs_team`; `paidInApp` is the sum of that team's non-cash, non-refunded positive payments.

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A -- src
git commit -m "feat(games): each team settles its own remainder, by card or in cash

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 6: "What happens if I cancel now", per team (spec STEP 6)

**Files:**
- Modify: `dto/response/GameTeamResponse.java`, `service/TeamGameService.java`
- Test: `src/test/java/uz/minipolya/minipolya_backend/service/TeamGameRefundPreviewTest.java` (new)

**Interfaces:**
- Produces: `GameTeamResponse.cancellationDeadline : OffsetDateTime`, `GameTeamResponse.refundIfCancelledNow : Long`, and `TeamGameService.refundIfCancelledNow(Game game, GameTeam team, OffsetDateTime now) -> long` — the single function both the preview and `cancelByLeader` consult.

- [ ] **Step 1: Write the failing tests**

Each case asserts the preview equals what the cancellation actually moves:

```java
@Test void previewMatchesAnEarlyCancellation()      // > deadline away: equals fee + advance (+ remainder if paid)
@Test void previewMatchesALateCancellation()        // past the deadline, before T-3h: 0
@Test void previewMatchesACancellationPastTheThreeHourPoint()   // canceller: 0
@Test void previewForAWaitingTeamPastTheThreeHourPointIsEverything()  // the auto-cancel refunds them
@Test void previewIsZeroOnceThatTeamHasBeenRefunded()
@Test void theDeadlineIsStartMinusTheStadiumsCancellationHours()
```

Each of the first four calls `refundIfCancelledNow(...)`, then runs the matching cancellation with the same `now`, and asserts the tiyin actually returned equals the preview.

- [ ] **Step 2: Run and confirm failure**

Run: `./mvnw test -Dtest=TeamGameRefundPreviewTest` → FAIL.

- [ ] **Step 3: Implement**

`refundIfCancelledNow` applies the same band logic as `cancelByLeader` — past the 3-hour point a canceller gets 0 while a lone waiting team gets everything, otherwise `cancellationPolicy.refundable(...)` decides — and returns `paymentService.refundableTeamTotal(team.getId())` or 0. `cancelByLeader` is refactored to ask the same helper whether to give back or keep, so the two cannot drift. Fill both fields wherever `GameTeamResponse` is built.

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A -- src
git commit -m "feat(games): each team sees its cancellation deadline and live refund figure

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 7: Finding opponents (spec STEP 7)

**Files:**
- Create: `dto/response/GameChallengeResponse.java`
- Modify: `repository/GameRepository.java`, `service/TeamGameService.java`, `controller/GameController.java`
- Modify: `service/AvailabilityService.java`, `service/OwnerDashboardService.java`, `dto/response/OwnerScheduleResponse.java`
- Test: `src/test/java/uz/minipolya/minipolya_backend/service/TeamGameChallengesTest.java` (new)
- Test: `service/AvailabilityServiceTest.java`, `service/OwnerDashboardServiceTest.java` (new cases)

**Interfaces:**
- Produces:
  - `GET /api/games/challenges?date=&format=&stadiumId=&page=&size=` → `PageResponse<GameChallengeResponse>`
  - `GameChallengeResponse { UUID gameId; UUID waitingTeamId; String waitingTeamName; UUID stadiumId; String stadiumName; String stadiumAddress; String format; OffsetDateTime startsAt; OffsetDateTime endsAt; Integer durationHours; Long totalPrice; Long amountToPayNow; Long feeAmount; Long advanceAmount; Long remainderAmount; }`
  - `TeamGameService.challenges(UUID userId, LocalDate date, String format, UUID stadiumId, Pageable) -> Page<GameChallengeResponse>`
  - `OwnerScheduleResponse.waitingGames : List<OwnerScheduleResponse.WaitingGame>` with `{ UUID gameId; String teamName; OffsetDateTime startsAt; OffsetDateTime endsAt; }`

**Note:** the spec asks the challenge card for the stadium's *district*. `stadiums` has no district column — only `address` — so `stadiumAddress` carries it. Flagged in the final report.

- [ ] **Step 1: Write the failing tests**

```java
@Test void challengesListWaitingTeamGamesBeforeTheirThreeHourPoint()
@Test void challengesExcludeGamesStartingWithinThreeHours()
@Test void challengesExcludeCasualAndOpenGames()
@Test void challengesExcludeGamesWhereTheCallerIsOnTheWaitingTeam()
@Test void challengesFilterByDateFormatAndStadium()
@Test void aChallengeShowsWhatTheJoiningTeamPaysNow() {
    assertThat(first().getAmountToPayNow()).isEqualTo(5_000_000L);   // fee 25 000 + advance 25 000
}
```

In `AvailabilityServiceTest`: a waiting `team_vs_team` game marks its slot `hasWaitingGame`, and a waiting **casual** game no longer does. In `OwnerDashboardServiceTest`: a waiting team game appears in `waitingGames` for its day.

- [ ] **Step 2: Run and confirm failure**

Run: `./mvnw test -Dtest=TeamGameChallengesTest` → FAIL.

- [ ] **Step 3: Implement**

- `GameRepository.findChallenges(...)` — a `@Query` over `Game` filtered on `type = 'team_vs_team'`, `status = 'open'`, `startsAt > :cutoff`, optional `stadium.id`, optional `stadium.format`, optional `startsAt` inside the Tashkent day, ordered by `startsAt`, paged. Exclude games whose active side-A `GameTeam.team` is in the caller's team ids (`teamMemberRepository.findByUserId`, plus teams they lead).
- `AvailabilityService`: filter the waiting-game lookup to `type = 'team_vs_team'`.
- `OwnerDashboardService.getSchedule`: load waiting `team_vs_team` games in the range and group them by Tashkent date into the new field.

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A -- src
git commit -m "feat(games): opponents can find waiting team games, and schedules show them

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 8: Chat (spec STEP 8)

**Files:**
- Modify: `service/GameMessageService.java`, `repository/GameMessageRepository.java`
- Test: `src/test/java/uz/minipolya/minipolya_backend/service/GameMessageServiceTest.java`

**Interfaces:**
- Consumes: `GameTeamRepository.findByGameIdAndSideAndStatus(...)` (Task 1) for the viewer's `joinedAt`.
- Produces: `GameMessageRepository.findByGameIdAndCreatedAtAfterOrderByCreatedAtDesc(UUID, OffsetDateTime, Pageable)`.

- [ ] **Step 1: Update the existing tests and add the new ones**

The two tests that currently encode "chat is open while the game is `open`" invert:

```java
@Test void chatIsClosedWhileTheGameIsStillLookingForAnOpponent() {
    game.setStatus("open");
    assertThatThrownBy(() -> service.sendMessage(GAME_ID, LEADER_A_ID, "hi"))
            .isInstanceOf(BadRequestException.class).hasMessage("Chat is closed for this game");
}

@Test void chatIsOpenWhileTheGameIsConfirmed()
@Test void chatIsClosedOnceTheGameIsCompleted()

@Test void aNewOpponentSeesOnlyMessagesSentAfterTheyJoined() {
    // team B joined at T; one message before T, one after
    assertThat(service.getMessages(GAME_ID, LEADER_B_ID, page()).getContent()).hasSize(1);
}

@Test void theTeamThatStayedStillSeesTheWholeHistory() {
    assertThat(service.getMessages(GAME_ID, LEADER_A_ID, page()).getContent()).hasSize(2);
}
```

- [ ] **Step 2: Run and confirm failure**

Run: `./mvnw test -Dtest=GameMessageServiceTest` → FAIL.

- [ ] **Step 3: Implement**

Drop `"open"` from the open-chat condition in `sendMessage`, and apply the same gate to `getMessages`. For a `team_vs_team` game, look up the caller's active `GameTeam`; when there is one, read messages with `createdAt > gameTeam.joinedAt`. A caller with no `GameTeam` (an invited member) keeps the full history of their side's tenure — use their team's active `GameTeam.joinedAt` when they belong to one, else the whole history.

- [ ] **Step 4: Run and confirm it passes**

Run: `./mvnw test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A -- src
git commit -m "feat(games): chat opens on confirmation and a new opponent starts fresh

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 9: Casual games read their fee from platform settings (spec STEP 9)

**Files:**
- Modify: `entity/Game.java` (drop the stale `500_000L` field default), possibly nothing else
- Test: `service/GameServiceTest.java`

**Note:** `GameService.serviceFeeFor(type)` already returns `settings.getCasualGameServiceFee()` for non-team games, and `AppProperties` no longer has `defaultServiceFeePerPerson` — `docs/requirements-audit.md` G1 is stale here. This task **verifies and pins** that with a test rather than reimplementing it. If the verification shows the behaviour is already correct and nothing needs changing beyond the entity default, say so in the final report rather than inventing a change.

- [ ] **Step 1: Write the pinning test**

```java
@Test void aCasualGameTakesItsServiceFeeFromPlatformSettings() {
    when(platformSettingsService.current()).thenReturn(settings(/* casual fee */ 123_400L));
    service.createGame(casualRequest(), CREATOR_ID);
    assertThat(savedGame().getServiceFeePerPerson()).isEqualTo(123_400L);
}

@Test void aTeamGameTakesThePerTeamPlatformFeeInstead() {
    when(platformSettingsService.current()).thenReturn(settings(/* team fee */ 2_500_000L));
    service.createGame(teamRequest(), LEADER_A_ID);
    assertThat(savedGame().getServiceFeePerPerson()).isEqualTo(2_500_000L);
}
```

- [ ] **Step 2: Run it**

Run: `./mvnw test -Dtest=GameServiceTest` — expected PASS for the first, which confirms the behaviour already holds.

- [ ] **Step 3: Remove the stale entity default**

`Game.serviceFeePerPerson` still defaults to `500_000L` (the old 5 000 so'm), which no longer matches any setting. Drop the `@Builder.Default` so the value always comes from settings, and leave the DB default alone (it is harmless and dropping it is a pointless changeset).

- [ ] **Step 4: Run the full suite**

Run: `./mvnw test` → BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add -A -- src
git commit -m "test(games): pin the casual service fee to platform settings

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 10: CLAUDE.md and the Postman collection

**Files:**
- Modify: `CLAUDE.md`
- Modify: `postman/MiniPolya.postman_collection.json` (section `09 Games (casual & team_vs_team)`), `postman/MiniPolya.postman_environment.json` if new variables are needed

- [ ] **Step 1: Rewrite the team_vs_team section of CLAUDE.md**

Replace the `team_vs_team — team vs team` bullet list and the `creator_share_pct` / per-leader service-fee wording with: the three states (and that WAITING is persisted as `status = 'open'`), the amount formulas, the hold-then-capture order, the three cancellation bands, the 3-hour rule and its config key, per-team remainder settlement, `cancelled_opponent_left`, and the new endpoints. Add `game_teams` to the entity list, `025`/`026` to the changeset list, and note that a team game's booking is confirmed by `TeamGameService`, not by `applySuccessfulBookingPayment`.

- [ ] **Step 2: Update the Postman collection**

In section 09, delete the four `.../pay?provider=payme` + webhook requests for the team game and replace them with the real flow: create with a `cardId` (holds), list `GET /api/games/challenges`, join with `teamId` + `cardId` (confirms), `POST /api/games/{id}/pay-remainder`, `POST /api/games/{id}/cash`, `DELETE /api/games/{id}` reopening the game, a second opponent joining, and the failure cases (join after the 3-hour point → 400 with the Uzbek message, declined card via the `0002` card, paying a remainder twice → 400). Keep the casual-game requests as they are.

- [ ] **Step 3: Verify**

Run: `./mvnw test` → BUILD SUCCESS. Confirm the collection still parses:
`powershell -c "Get-Content postman/MiniPolya.postman_collection.json -Raw | ConvertFrom-Json | Out-Null"`

- [ ] **Step 4: Commit**

```bash
git add -A -- CLAUDE.md postman
git commit -m "docs(games): document and exercise the new team game money flow

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## Self-Review Notes

- **Spec coverage:** steps 1–9 map to Tasks 1–9; the CLAUDE.md and Postman asks map to Task 10. The "open" game type is untouched throughout, as asked.
- **Known gap carried forward:** the challenge card's "district" has no column to read — `stadiumAddress` stands in (Task 7).
- **Deliberate reading:** "remove the team_vs_team payment path" removes the *team branches* of `/pay`, `isFullyFunded`, `gameShareAmount` and `creator_share_pct`, because the same methods are the casual flow the spec says to keep.
- **Deliberate reading:** the spec's three cancellation bands assume `cancellation_hours >= 3`. When they overlap, the 3-hour band wins (Review Focus 3).
- **Open question for the final report:** no booking commission is applied to team-game payments — the platform's cut is the per-team fee, and charging both would double-count.
