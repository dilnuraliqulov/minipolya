package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditActorType;
import uz.minipolya.minipolya_backend.audit.AuditChange;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.Audited;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.repository.TeamMemberRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The three ways a waiting team game ends without ever finding an opponent:
 * somebody books the pitch directly, the 3-hour point passes, or the waiting
 * leader gives up.
 *
 * <p>The stadium's cancellation window is 24 hours and the opponent deadline is
 * 3, so there are three bands before kick-off: more than 24 h out (everything
 * back), between 24 h and 3 h (nothing back), and inside 3 h — where the
 * timeout job would have refunded them anyway, so beating it by hand must not
 * cost the team its money.
 */
class TeamGameWaitingEndTest {

    private static final long FEE = 2_500_000L;
    private static final long ADVANCE = 2_500_000L;

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID LEADER_A_ID = UUID.randomUUID();
    private static final UUID STRANGER_ID = UUID.randomUUID();

    private GameRepository gameRepository;
    private GameTeamRepository gameTeamRepository;
    private PaymentService paymentService;
    private AuditService auditService;
    private TeamGameService service;

    private Stadium stadium;
    private User leaderA;
    private Team teamA;
    private GameTeam sideA;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        gameTeamRepository = mock(GameTeamRepository.class);
        paymentService = mock(PaymentService.class);
        auditService = mock(AuditService.class);
        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.current()).thenReturn(
                PlatformSettings.builder().teamGamePlatformFee(FEE).build());

        service = new TeamGameService(gameRepository, gameTeamRepository,
                mock(GameParticipantRepository.class), mock(BookingRepository.class),
                paymentService, mock(SavedCardService.class), platformSettingsService,
                mock(NotificationService.class), auditService,
                new CancellationPolicy(), new AppProperties(),
                mock(TeamMemberRepository.class), mock(TeamRepository.class));

        leaderA = User.builder().id(LEADER_A_ID).name("Leader A").build();
        stadium = Stadium.builder().id(UUID.randomUUID()).name("Chilonzor Arena").cancellationHours(24)
                .owner(User.builder().id(UUID.randomUUID()).name("Owner").build()).build();
        teamA = Team.builder().id(UUID.randomUUID()).name("Team A").leader(leaderA).build();

        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameTeamRepository.save(any(GameTeam.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Game waitingGame(Duration until) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plus(until);
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("open")
                .creator(leaderA).stadium(stadium).teamA(teamA)
                .startsAt(start).endsAt(start.plusHours(1))
                .totalPrice(20_000_000L).maxPlayers(10).build();
        sideA = GameTeam.builder().id(UUID.randomUUID()).game(game).team(teamA).leader(leaderA)
                .side(Side.A).status(GameTeam.Status.ACTIVE)
                .feeAmount(FEE).advanceAmount(ADVANCE).remainderAmount(7_500_000L)
                .remainderStatus(GameTeam.RemainderStatus.UNPAID).build();
        when(gameTeamRepository.findByGameIdAndStatus(GAME_ID, GameTeam.Status.ACTIVE))
                .thenReturn(List.of(sideA));
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, LEADER_A_ID, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.of(sideA));
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, STRANGER_ID, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.empty());
        return game;
    }

    // ---- how long the creating team's money is held ----

    @Test
    void theHoldLastsUntilTheThreeHourPointPlusTwoHours() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Game game = waitingGame(Duration.ofDays(2));

        int minutes = service.holdMinutes(game, now);

        long untilCutoff = Duration.between(now, game.getStartsAt().minusHours(3)).toMinutes();
        assertThat(minutes).isEqualTo((int) untilCutoff + 120);
    }

    @Test
    void aGameTooFarAheadForTheHoldCapCannotBeCreated() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        Game game = waitingGame(Duration.ofDays(31));   // the default cap is 43 200 min = 30 days

        assertThatThrownBy(() -> service.holdMinutes(game, now))
                .isInstanceOf(uz.minipolya.minipolya_backend.exception.BadRequestException.class)
                .hasMessageContaining("days before it is played");
    }

    // ---- somebody books the pitch ----

    @Test
    void aDirectBookingOnTheSlotCancelsTheGameAndGivesTheHoldBack() {
        Game game = waitingGame(Duration.ofDays(2));

        service.endWaitingGame(game, "cancelled_slot_taken", "Slot taken by another booking");

        assertThat(game.getStatus()).isEqualTo("cancelled_slot_taken");
        verify(paymentService).giveBackAllTeamPayments(eq(sideA.getId()), anyString());
        AuditEntry entry = Audited.one(auditService, AuditAction.GAME_AUTO_CANCELLED);
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(entry.getChanges()).containsExactly(AuditChange.of("status", "open", "cancelled_slot_taken"));
        assertThat(sideA.getStatus()).isEqualTo(GameTeam.Status.CANCELLED);
        assertThat(sideA.getLeftAt()).isNotNull();
    }

    // ---- the waiting leader gives up ----

    @Test
    void cancellingBeforeTheDeadlineGivesEverythingBack() {
        Game game = waitingGame(Duration.ofDays(2));

        service.cancelByLeader(game, LEADER_A_ID);

        verify(paymentService).giveBackAllTeamPayments(eq(sideA.getId()), anyString());
        verify(paymentService, never()).keepAllTeamPayments(any());
        assertThat(game.getStatus()).isEqualTo("cancelled_by_creator");
    }

    @Test
    void cancellingAfterTheDeadlineGivesNothingBack() {
        Game game = waitingGame(Duration.ofHours(10));   // past 24 h, not yet inside 3 h

        service.cancelByLeader(game, LEADER_A_ID);

        verify(paymentService).keepAllTeamPayments(sideA.getId());
        verify(paymentService, never()).giveBackAllTeamPayments(any(), anyString());
        assertThat(game.getStatus()).isEqualTo("cancelled_by_creator");
        assertThat(Audited.one(auditService, AuditAction.GAME_CANCELLED).getReason())
                .isEqualTo("Cancelled after the deadline: nothing back");
    }

    @Test
    void cancellingPastTheThreeHourPointGivesEverythingBack() {
        Game game = waitingGame(Duration.ofHours(2));

        service.cancelByLeader(game, LEADER_A_ID);

        // The timeout job would have refunded them; getting there first must not cost them.
        verify(paymentService).giveBackAllTeamPayments(eq(sideA.getId()), anyString());
        verify(paymentService, never()).keepAllTeamPayments(any());
        assertThat(game.getStatus()).isEqualTo("cancelled_not_filled");
        // The leader cancelled it, so it is their entry — not a SYSTEM auto-cancel.
        AuditEntry entry = Audited.one(auditService, AuditAction.GAME_CANCELLED);
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.PLAYER);
        assertThat(entry.getActorId()).isEqualTo(LEADER_A_ID);
        assertThat(entry.getChanges()).containsExactly(AuditChange.of("status", "open", "cancelled_not_filled"));
        assertThat(Audited.entries(auditService, AuditAction.GAME_AUTO_CANCELLED)).isEmpty();
    }

    @Test
    void onlyALeaderOfATeamInTheGameMayCancelIt() {
        Game game = waitingGame(Duration.ofDays(2));

        assertThatThrownBy(() -> service.cancelByLeader(game, STRANGER_ID))
                .isInstanceOf(ForbiddenException.class);

        verify(paymentService, never()).giveBackAllTeamPayments(any(), anyString());
        assertThat(game.getStatus()).isEqualTo("open");
    }

    // ---- the refund preview agrees with what happens ----

    @Test
    void thePreviewMatchesAnEarlyCancellation() {
        Game game = waitingGame(Duration.ofDays(2));
        when(paymentService.refundableTeamTotal(sideA.getId())).thenReturn(FEE + ADVANCE);

        long preview = service.refundIfCancelledNow(game, sideA, OffsetDateTime.now(ZoneOffset.UTC));

        assertThat(preview).isEqualTo(FEE + ADVANCE);
    }

    @Test
    void thePreviewIsZeroPastTheDeadline() {
        Game game = waitingGame(Duration.ofHours(10));
        when(paymentService.refundableTeamTotal(sideA.getId())).thenReturn(FEE + ADVANCE);

        assertThat(service.refundIfCancelledNow(game, sideA, OffsetDateTime.now(ZoneOffset.UTC))).isZero();
    }

    @Test
    void thePreviewForALoneWaitingTeamPastTheThreeHourPointIsEverything() {
        Game game = waitingGame(Duration.ofHours(2));
        when(paymentService.refundableTeamTotal(sideA.getId())).thenReturn(FEE + ADVANCE);

        assertThat(service.refundIfCancelledNow(game, sideA, OffsetDateTime.now(ZoneOffset.UTC)))
                .isEqualTo(FEE + ADVANCE);
    }
}
