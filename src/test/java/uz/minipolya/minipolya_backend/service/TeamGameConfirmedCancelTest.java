package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.Booking;
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
 * One team walks out of a confirmed game. The slot is freed either way, but
 * what happens next depends on how close to kick-off it is:
 *
 * <ul>
 *   <li>more than 24 h out — the canceller gets everything back and the game
 *       goes back to looking for an opponent;</li>
 *   <li>between 24 h and 3 h — the canceller gets nothing, but the game still
 *       goes back to waiting;</li>
 *   <li>inside 3 h — nobody could replace them, so the game is over and the
 *       team that stayed gets everything back.</li>
 * </ul>
 */
class TeamGameConfirmedCancelTest {

    private static final long FEE = 2_500_000L;
    private static final long ADVANCE = 2_500_000L;
    private static final long PRICE = 20_000_000L;

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID LEADER_A_ID = UUID.randomUUID();
    private static final UUID LEADER_B_ID = UUID.randomUUID();
    private static final UUID STRANGER_ID = UUID.randomUUID();

    private GameRepository gameRepository;
    private GameTeamRepository gameTeamRepository;
    private BookingRepository bookingRepository;
    private PaymentService paymentService;
    private TeamGameService service;

    private Stadium stadium;
    private Team teamA;
    private Team teamB;
    private GameTeam sideA;
    private GameTeam sideB;
    private Booking booking;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        gameTeamRepository = mock(GameTeamRepository.class);
        bookingRepository = mock(BookingRepository.class);
        paymentService = mock(PaymentService.class);
        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.current()).thenReturn(
                PlatformSettings.builder().teamGamePlatformFee(FEE).build());

        service = new TeamGameService(gameRepository, gameTeamRepository,
                mock(GameParticipantRepository.class), bookingRepository,
                paymentService, mock(SavedCardService.class), platformSettingsService,
                mock(NotificationService.class), mock(AuditService.class),
                new CancellationPolicy(), new AppProperties());

        User leaderA = User.builder().id(LEADER_A_ID).name("Leader A").build();
        User leaderB = User.builder().id(LEADER_B_ID).name("Leader B").build();
        stadium = Stadium.builder().id(UUID.randomUUID()).name("Chilonzor Arena").cancellationHours(24)
                .owner(User.builder().id(UUID.randomUUID()).name("Owner").build()).build();
        teamA = Team.builder().id(UUID.randomUUID()).name("Team A").leader(leaderA).build();
        teamB = Team.builder().id(UUID.randomUUID()).name("Team B").leader(leaderB).build();

        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameTeamRepository.save(any(GameTeam.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Game confirmedGame(Duration until) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plus(until);
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("confirmed")
                .creator(teamA.getLeader()).stadium(stadium).teamA(teamA).teamB(teamB)
                .bookingId(BOOKING_ID).startsAt(start).endsAt(start.plusHours(1))
                .totalPrice(PRICE).maxPlayers(10).build();
        sideA = entry(game, teamA, Side.A);
        sideB = entry(game, teamB, Side.B);
        booking = Booking.builder().id(BOOKING_ID).stadium(stadium).user(teamA.getLeader())
                .source("game").status("confirmed").gameId(GAME_ID)
                .startsAt(start).endsAt(start.plusHours(1)).durationHours(1)
                .price(PRICE).paidAmount(2 * ADVANCE)
                .paymentStatus(Booking.PaymentStatus.PARTIALLY_PAID).build();

        when(gameTeamRepository.findByGameIdAndStatus(GAME_ID, GameTeam.Status.ACTIVE))
                .thenAnswer(inv -> List.of(sideA, sideB).stream()
                        .filter(t -> t.getStatus() == GameTeam.Status.ACTIVE).toList());
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, LEADER_A_ID, GameTeam.Status.ACTIVE))
                .thenAnswer(inv -> sideA.getStatus() == GameTeam.Status.ACTIVE
                        ? Optional.of(sideA) : Optional.empty());
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, LEADER_B_ID, GameTeam.Status.ACTIVE))
                .thenAnswer(inv -> sideB.getStatus() == GameTeam.Status.ACTIVE
                        ? Optional.of(sideB) : Optional.empty());
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, STRANGER_ID, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.empty());
        when(gameTeamRepository.findByGameId(GAME_ID)).thenAnswer(inv -> List.of(sideA, sideB));
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        return game;
    }

    private GameTeam entry(Game game, Team team, Side side) {
        return GameTeam.builder().id(UUID.randomUUID()).game(game).team(team).leader(team.getLeader())
                .side(side).status(GameTeam.Status.ACTIVE)
                .feeAmount(FEE).advanceAmount(ADVANCE).remainderAmount(7_500_000L)
                .remainderStatus(GameTeam.RemainderStatus.UNPAID).build();
    }

    // ---- before the deadline ----

    @Test
    void cancellingBeforeTheDeadlineRefundsTheCancellerAndReopensTheGame() {
        Game game = confirmedGame(Duration.ofDays(2));

        service.cancelByLeader(game, LEADER_B_ID);

        verify(paymentService).giveBackAllTeamPayments(eq(sideB.getId()), anyString());
        verify(paymentService, never()).giveBackAllTeamPayments(eq(sideA.getId()), anyString());
        assertThat(game.getStatus()).isEqualTo("open");
        assertThat(game.getTeamB()).isNull();
        assertThat(game.getBookingId()).isNull();
        assertThat(booking.getStatus()).isEqualTo("cancelled_opponent_left");
    }

    @Test
    void theRemainingTeamStaysInTheGameWithItsMoneyUntouched() {
        Game game = confirmedGame(Duration.ofDays(2));

        service.cancelByLeader(game, LEADER_B_ID);

        assertThat(sideA.getStatus()).isEqualTo(GameTeam.Status.ACTIVE);
        assertThat(sideB.getStatus()).isEqualTo(GameTeam.Status.CANCELLED);
        assertThat(sideB.getLeftAt()).isNotNull();
        verify(paymentService, never()).keepAllTeamPayments(sideA.getId());
    }

    @Test
    void eitherLeaderCanCancel() {
        Game game = confirmedGame(Duration.ofDays(2));

        service.cancelByLeader(game, LEADER_A_ID);

        assertThat(sideA.getStatus()).isEqualTo(GameTeam.Status.CANCELLED);
        assertThat(sideB.getStatus()).isEqualTo(GameTeam.Status.ACTIVE);
        assertThat(game.getTeamA()).isNull();
        assertThat(game.getStatus()).isEqualTo("open");
    }

    // ---- after the deadline, before the 3-hour point ----

    @Test
    void cancellingAfterTheDeadlineRefundsNothingButStillReopensTheGame() {
        Game game = confirmedGame(Duration.ofHours(10));

        service.cancelByLeader(game, LEADER_B_ID);

        verify(paymentService).keepAllTeamPayments(sideB.getId());
        verify(paymentService, never()).giveBackAllTeamPayments(eq(sideB.getId()), anyString());
        assertThat(game.getStatus()).isEqualTo("open");
    }

    // ---- past the 3-hour point ----

    @Test
    void cancellingPastTheThreeHourPointEndsTheGameAndRefundsTheRemainingTeam() {
        Game game = confirmedGame(Duration.ofHours(2));

        service.cancelByLeader(game, LEADER_B_ID);

        verify(paymentService).keepAllTeamPayments(sideB.getId());
        verify(paymentService).giveBackAllTeamPayments(eq(sideA.getId()), anyString());
        assertThat(game.getStatus()).isEqualTo("cancelled_not_filled");
        assertThat(booking.getStatus()).isEqualTo("cancelled_opponent_left");
    }

    @Test
    void pastThreeHourPointBeatsAStillOpenDeadline() {
        stadium.setCancellationHours(1);   // deadline is T-1h, i.e. after the 3-hour point
        Game game = confirmedGame(Duration.ofHours(2));

        service.cancelByLeader(game, LEADER_B_ID);

        verify(paymentService).keepAllTeamPayments(sideB.getId());
        verify(paymentService, never()).giveBackAllTeamPayments(eq(sideB.getId()), anyString());
        assertThat(game.getStatus()).isEqualTo("cancelled_not_filled");
    }

    @Test
    void cancellingAfterKickOffRefundsNothingAndEndsTheGame() {
        Game game = confirmedGame(Duration.ofHours(-1));

        service.cancelByLeader(game, LEADER_B_ID);

        verify(paymentService).keepAllTeamPayments(sideB.getId());
        verify(paymentService, never()).giveBackAllTeamPayments(eq(sideB.getId()), anyString());
        assertThat(game.getStatus()).isEqualTo("cancelled_not_filled");
    }

    @Test
    void aNonLeaderCannotCancel() {
        Game game = confirmedGame(Duration.ofDays(2));

        assertThatThrownBy(() -> service.cancelByLeader(game, STRANGER_ID))
                .isInstanceOf(ForbiddenException.class);

        assertThat(game.getStatus()).isEqualTo("confirmed");
    }

    // ---- the owner or an admin cancels the booking ----

    @Test
    void theOwnerCancellingTheBookingRefundsBothTeamsAndEndsTheGame() {
        Game game = confirmedGame(Duration.ofHours(10));   // past the deadline: still everything back

        service.cancelForBookingCancelled(GAME_ID, "Pitch flooded");

        verify(paymentService).giveBackAllTeamPayments(eq(sideA.getId()), anyString());
        verify(paymentService).giveBackAllTeamPayments(eq(sideB.getId()), anyString());
        verify(paymentService, never()).keepAllTeamPayments(any());
        assertThat(game.getStatus()).isEqualTo("cancelled_slot_taken");
        assertThat(sideA.getStatus()).isEqualTo(GameTeam.Status.CANCELLED);
        assertThat(sideB.getStatus()).isEqualTo(GameTeam.Status.CANCELLED);
    }

    // ---- the preview agrees with the refund ----

    @Test
    void thePreviewMatchesEachBandForTheCancellingTeam() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        when(paymentService.refundableTeamTotal(any())).thenReturn(FEE + ADVANCE);

        assertThat(service.refundIfCancelledNow(confirmedGame(Duration.ofDays(2)), sideB, now))
                .isEqualTo(FEE + ADVANCE);
        assertThat(service.refundIfCancelledNow(confirmedGame(Duration.ofHours(10)), sideB, now))
                .isZero();
        assertThat(service.refundIfCancelledNow(confirmedGame(Duration.ofHours(2)), sideB, now))
                .isZero();
    }
}
