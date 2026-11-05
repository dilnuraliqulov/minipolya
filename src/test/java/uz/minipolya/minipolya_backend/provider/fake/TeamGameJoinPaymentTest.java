package uz.minipolya.minipolya_backend.provider.fake;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.service.TeamGameService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Joining a team game is book slot → charge the joiner → capture the creator's hold. Whatever
 * the capture answers, nothing is left half-done: a refusal (any code) undoes everything, an
 * unclear answer is left for the reconcile job, which then confirms or undoes the game.
 */
@DatabaseTest
class TeamGameJoinPaymentTest extends FakeProviderFlowSupport {

    @Autowired
    private GameTeamRepository gameTeams;
    @Autowired
    private GameParticipantRepository participants;
    @Autowired
    private TeamGameService teamGameService;
    @Autowired
    private uz.minipolya.minipolya_backend.service.GameService gameService;
    @Autowired
    private jakarta.persistence.EntityManager entityManager;
    /** The real fake, spied only to simulate a crash. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private uz.minipolya.minipolya_backend.provider.PaymentProvider provider;
    /** Notifications commit in their own transaction, which cannot see this test's users: watched here instead. */
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private uz.minipolya.minipolya_backend.service.NotificationService notificationService;

    /** The next charge confirmation takes the money, then the server "dies" before recording it. */
    private void crashAfterNextChargeConfirmation() {
        org.mockito.Mockito.doAnswer(inv -> {
                    inv.callRealMethod();
                    throw new IllegalStateException("simulated crash");
                })
                .doCallRealMethod()
                .when(provider).confirmCharge(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.any());
    }

    private record Setup(User leaderA, User leaderB, UUID cardA, UUID cardB, UUID gameId, UUID teamB, Payment hold) {
    }

    private Setup waitingGame(String creatorCard, int hour) throws Exception {
        User leaderA = data.user("Join A " + hour);
        User leaderB = data.user("Join B " + hour);
        UUID cardA = addCard(leaderA, creatorCard);
        UUID cardB = addCard(leaderB, "9860123412345678");
        UUID gameId = createTeamGame(leaderA, team(leaderA, "Join Team A " + hour), cardA, slot(3, hour));
        Payment hold = payments.findByGameId(gameId).getFirst();
        assertThat(hold.getStatus()).isEqualTo(Payment.Status.HELD);
        return new Setup(leaderA, leaderB, cardA, cardB, gameId, team(leaderB, "Join Team B " + hour), hold);
    }

    private GameTeam joiningEntry(UUID gameId) {
        return gameTeams.findByGameId(gameId).stream()
                .filter(t -> !t.getLeader().getId().equals(games.findById(gameId).orElseThrow().getCreator().getId()))
                .findFirst().orElseThrow();
    }

    private Payment joinerPayment(UUID gameId) {
        return payments.findByGameTeamId(joiningEntry(gameId).getId()).stream()
                .filter(p -> p.getAmount() > 0).findFirst().orElseThrow();
    }

    private boolean slotIsFree(Game game) {
        return bookings.findAll().stream()
                .filter(b -> b.getStadium().getId().equals(game.getStadium().getId()))
                .filter(b -> b.getStartsAt().isEqual(game.getStartsAt()))
                .noneMatch(b -> List.of("pending", "confirmed").contains(b.getStatus()));
    }

    private List<UUID> notifiedOfCancellation(UUID gameId) {
        var userIds = org.mockito.ArgumentCaptor.forClass(UUID.class);
        org.mockito.Mockito.verify(notificationService, org.mockito.Mockito.atLeastOnce()).notify(userIds.capture(),
                org.mockito.ArgumentMatchers.eq(Notification.Type.GAME_CANCELLED), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(gameId));
        return userIds.getAllValues();
    }

    private void assertUndone(Setup s) {
        Game game = games.findById(s.gameId()).orElseThrow();
        assertThat(game.getStatus()).startsWith("cancelled");
        assertThat(slotIsFree(game)).isTrue();
        assertThat(payments.findById(s.hold().getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.RELEASED);
        assertThat(joinerPayment(s.gameId()).getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(balance(s.cardA())).isEqualTo(START);
        assertThat(balance(s.cardB())).isEqualTo(START);
        assertThat(gameTeams.findByGameId(s.gameId())).extracting(GameTeam::getStatus)
                .containsOnly(GameTeam.Status.CANCELLED);
        assertThat(notifiedOfCancellation(s.gameId())).contains(s.leaderA().getId(), s.leaderB().getId());
    }

    // ---- a definite refusal ----

    @Test
    void aCaptureRefusedWithAnyCodeUndoesEverything() throws Exception {
        Setup s = waitingGame("8600123412340097", 10);

        join(s.leaderB(), s.gameId(), s.teamB(), s.cardB())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "The other team's payment could not be taken, so the game is cancelled; "
                                + "your payment is being returned in full"));

        assertUndone(s);
        // The audit names why, with the provider's code.
        entityManager.flush();
        assertThat(jdbc.queryForList("SELECT reason FROM audit_log WHERE entity_id = ? AND action = 'GAME_AUTO_CANCELLED'",
                String.class, s.gameId())).anyMatch(r -> r.contains("STPIMS-ERR-058"));
    }

    // ---- an unclear answer ----

    @Test
    void anUnclearCaptureThatWentThroughIsConfirmedByTheReconcileJob() throws Exception {
        Setup s = waitingGame("8600123412340091", 11);

        join(s.leaderB(), s.gameId(), s.teamB(), s.cardB())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.code").value("PAYMENT_PENDING"));

        // Half-way, but every step is on record: nothing is lost, nothing confirmed yet.
        assertThat(games.findById(s.gameId()).orElseThrow().getStatus()).isEqualTo("open");
        assertThat(joiningEntry(s.gameId()).getStatus()).isEqualTo(GameTeam.Status.JOINING);
        assertThat(joinerPayment(s.gameId()).getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(payments.findById(s.hold().getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.CAPTURE_PENDING);

        reconcile();

        Game game = games.findById(s.gameId()).orElseThrow();
        assertThat(game.getStatus()).isEqualTo("confirmed");
        assertThat(game.getBookingId()).isNotNull();
        assertThat(bookings.findById(game.getBookingId()).orElseThrow().getStatus()).isEqualTo("confirmed");
        assertThat(joiningEntry(s.gameId()).getStatus()).isEqualTo(GameTeam.Status.ACTIVE);
        assertThat(payments.findById(s.hold().getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.CAPTURED);
        assertThat(participants.findByGameIdAndUserId(s.gameId(), s.leaderB().getId()))
                .get().extracting(GameParticipant::getSide).isEqualTo(joiningEntry(s.gameId()).getSide());
    }

    @Test
    void anUnclearCaptureThatFailedIsUndoneByTheReconcileJob() throws Exception {
        Setup s = waitingGame("8600123412340092", 12);

        join(s.leaderB(), s.gameId(), s.teamB(), s.cardB())
                .andExpect(status().isAccepted());
        assertThat(payments.findById(s.hold().getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.CAPTURE_PENDING);

        reconcile();

        assertUndone(s);
    }

    // ---- a crash between the steps ----

    @Test
    void aCrashRightAfterTheJoinerWasChargedIsFinishedByTheReconcileJob() throws Exception {
        Setup s = waitingGame("8600123412341111", 13);
        crashAfterNextChargeConfirmation();

        join(s.leaderB(), s.gameId(), s.teamB(), s.cardB())
                .andExpect(status().isInternalServerError());

        // The money moved, and the record of it survived the crash: nothing lost.
        assertThat(balance(s.cardB())).isLessThan(START);
        assertThat(joinerPayment(s.gameId()).getStatus()).isEqualTo(Payment.Status.PROCESSING);
        assertThat(joinerPayment(s.gameId()).getProviderTxnId()).isNotBlank();
        assertThat(joiningEntry(s.gameId()).getStatus()).isEqualTo(GameTeam.Status.JOINING);

        reconcile();

        assertThat(games.findById(s.gameId()).orElseThrow().getStatus()).isEqualTo("confirmed");
        assertThat(joinerPayment(s.gameId()).getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(payments.findById(s.hold().getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.CAPTURED);
    }

    @Test
    void aJoinWhoseGameEndedMeanwhileIsUndoneAndTheJoinerRefunded() throws Exception {
        Setup s = waitingGame("8600123412341112", 14);
        crashAfterNextChargeConfirmation();
        join(s.leaderB(), s.gameId(), s.teamB(), s.cardB()).andExpect(status().isInternalServerError());

        // While the join hangs, the waiting game is ended (its 3-hour point, a booking, its leader).
        Game game = games.findById(s.gameId()).orElseThrow();
        teamGameService.endWaitingGame(game, "cancelled_not_filled", "Game did not fill in time");

        reconcile();

        assertThat(joinerPayment(s.gameId()).getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(balance(s.cardB())).isEqualTo(START);
        assertThat(balance(s.cardA())).isEqualTo(START);
        assertThat(slotIsFree(games.findById(s.gameId()).orElseThrow())).isTrue();
    }

    // ---- the ordinary join, and the joiner's own card ----

    @Test
    void aJoinChargesTheJoinerItsShareAndPartPaysTheBookingWithBothAdvances() throws Exception {
        Setup s = waitingGame("8600123412341113", 15);

        join(s.leaderB(), s.gameId(), s.teamB(), s.cardB()).andExpect(status().isOk());

        Game game = games.findById(s.gameId()).orElseThrow();
        assertThat(game.getStatus()).isEqualTo("confirmed");
        assertThat(gameTeams.findByGameId(s.gameId())).extracting(GameTeam::getStatus)
                .containsOnly(GameTeam.Status.ACTIVE);
        // The same fee and its own half of the minimum: an even minimum, so the same amount.
        assertThat(joinerPayment(s.gameId()).getAmount()).isEqualTo(s.hold().getAmount());
        var booking = bookings.findById(game.getBookingId()).orElseThrow();
        assertThat(booking.getStatus()).isEqualTo("confirmed");
        assertThat(booking.getPaidAmount()).isEqualTo(stadium.getMinBookingAmount());
        assertThat(booking.getPaymentStatus()).isEqualTo(uz.minipolya.minipolya_backend.entity.Booking.PaymentStatus.PARTIALLY_PAID);
        assertThat(participants.findByGameIdAndUserId(s.gameId(), s.leaderB().getId())).isPresent();
    }

    @Test
    void withoutAStadiumMinimumTheBookingIsPaidInFullOnConfirmation() throws Exception {
        stadium.setMinBookingAmount(null);
        stadiums.saveAndFlush(stadium);
        Setup s = waitingGame("8600123412341114", 16);

        join(s.leaderB(), s.gameId(), s.teamB(), s.cardB()).andExpect(status().isOk());

        Game game = games.findById(s.gameId()).orElseThrow();
        assertThat(bookings.findById(game.getBookingId()).orElseThrow().getPaymentStatus())
                .isEqualTo(uz.minipolya.minipolya_backend.entity.Booking.PaymentStatus.PAID);
    }

    @Test
    void aDeclinedJoinerLeavesTheGameWaitingWithTheSlotFree() throws Exception {
        Setup s = waitingGame("8600123412341115", 17);
        UUID declining = addCard(s.leaderB(), "8600123412340002");

        join(s.leaderB(), s.gameId(), s.teamB(), declining)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Card payment was declined; the game is still looking for an opponent"));

        assertThat(games.findById(s.gameId()).orElseThrow().getStatus()).isEqualTo("open");
        assertThat(joiningEntry(s.gameId()).getStatus()).isEqualTo(GameTeam.Status.CANCELLED);
        assertThat(slotIsFree(games.findById(s.gameId()).orElseThrow())).isTrue();
        assertThat(payments.findById(s.hold().getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.HELD);
        assertThat(balance(declining)).isEqualTo(START);
    }

    @Test
    void anUnansweredJoinerChargeIsGivenBackAndTheGameGoesOnWaiting() throws Exception {
        Setup s = waitingGame("8600123412341116", 18);
        UUID unclear = addCard(s.leaderB(), "8600123412340091");

        join(s.leaderB(), s.gameId(), s.teamB(), unclear)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "The card payment could not be confirmed; the game is still looking for an opponent. "
                                + "If the money was taken it will be returned in full"));
        assertThat(games.findById(s.gameId()).orElseThrow().getStatus()).isEqualTo("open");
        assertThat(joinerPayment(s.gameId()).getStatus()).isEqualTo(Payment.Status.PROCESSING);

        reconcile();

        // It went through after all: the team had left, so it all comes back.
        assertThat(joinerPayment(s.gameId()).getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(balance(unclear)).isEqualTo(START);
        assertThat(games.findById(s.gameId()).orElseThrow().getStatus()).isEqualTo("open");
        assertThat(payments.findById(s.hold().getId()).orElseThrow().getStatus()).isEqualTo(Payment.Status.HELD);
    }

    @Test
    void nobodyMayJoinWhileTheWaitingTeamsHoldIsStillUnconfirmed() throws Exception {
        Setup s = waitingGame("8600123412341117", 19);
        Payment hold = payments.findById(s.hold().getId()).orElseThrow();
        hold.setStatus(Payment.Status.PROCESSING);
        payments.saveAndFlush(hold);

        join(s.leaderB(), s.gameId(), s.teamB(), s.cardB())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "The other team's payment is still being confirmed; try again in a few minutes"));
        assertThat(balance(s.cardB())).isEqualTo(START);
    }

    @Test
    void aSecondOpponentCannotTakeTheSideWhileAJoinIsInProgress() throws Exception {
        Setup s = waitingGame("8600123412341118", 20);
        crashAfterNextChargeConfirmation();
        join(s.leaderB(), s.gameId(), s.teamB(), s.cardB()).andExpect(status().isInternalServerError());
        User leaderC = data.user("Third Leader");
        UUID cardC = addCard(leaderC, "8600123412349999");

        join(leaderC, s.gameId(), team(leaderC, "Team C"), cardC)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("This game already has an opponent"));
        assertThat(balance(cardC)).isEqualTo(START);
    }

    @Test
    void aNewOpponentTakesTheSideTheCreatorLeftAndPaysThatSidesHalf() throws Exception {
        Setup s = waitingGame("8600123412341119", 21);
        join(s.leaderB(), s.gameId(), s.teamB(), s.cardB()).andExpect(status().isOk());
        // The creating team walks out in time: the game waits again, with team B in.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/games/" + s.gameId())
                        .header("Authorization", data.bearer(s.leaderA())))
                .andExpect(status().isOk());
        assertThat(games.findById(s.gameId()).orElseThrow().getStatus()).isEqualTo("open");
        User leaderC = data.user("New Opponent");
        UUID cardC = addCard(leaderC, "8600123412348888");

        join(leaderC, s.gameId(), team(leaderC, "Team C2"), cardC)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.side").value("a"));

        assertThat(games.findById(s.gameId()).orElseThrow().getStatus()).isEqualTo("confirmed");
        GameTeam sideA = gameTeams.findByGameIdAndStatus(s.gameId(), GameTeam.Status.ACTIVE).stream()
                .filter(t -> t.getSide() == GameParticipant.Side.A).findFirst().orElseThrow();
        assertThat(sideA.getLeader().getId()).isEqualTo(leaderC.getId());
        assertThat(balance(cardC)).isEqualTo(START - payments.findByGameTeamId(sideA.getId()).getFirst().getAmount());
    }

    /** One run of the reconcile job, as if every row were old enough. */
    private void reconcile() {
        for (Payment p : payments.findAll()) {
            if (List.of(Payment.Status.PROCESSING, Payment.Status.RETURN_PENDING, Payment.Status.CAPTURE_PENDING)
                    .contains(p.getStatus())) {
                reconcileService.reconcile(p.getId());
            }
        }
        gameService.resumeStuckJoins(OffsetDateTime.now().plusYears(1));
    }
}
