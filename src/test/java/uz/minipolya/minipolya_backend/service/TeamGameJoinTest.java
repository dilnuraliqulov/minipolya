package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Booking.PaymentStatus;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.SlotAlreadyBookedException;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * An opponent joining a waiting team game. This is the moment the slot is
 * actually taken, so the order matters: book first, then charge the joiner,
 * then capture the waiting team's hold. Every failure along the way has to
 * leave nobody charged for a game that is not happening.
 */
class TeamGameJoinTest {

    private static final long PRICE = 20_000_000L;    // 200 000 so'm
    private static final long MINIMUM = 5_000_000L;   // 50 000 so'm
    private static final long FEE = 2_500_000L;       // 25 000 so'm
    private static final long ADVANCE = 2_500_000L;   // M / 2

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID LEADER_A_ID = UUID.randomUUID();
    private static final UUID LEADER_B_ID = UUID.randomUUID();
    private static final UUID TEAM_A_ID = UUID.randomUUID();
    private static final UUID TEAM_B_ID = UUID.randomUUID();
    private static final UUID CARD_B_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();

    private GameRepository gameRepository;
    private GameTeamRepository gameTeamRepository;
    private GameParticipantRepository participantRepository;
    private BookingRepository bookingRepository;
    private PaymentService paymentService;
    private SavedCardService savedCardService;
    private TeamGameService service;

    private Stadium stadium;
    private Team teamA;
    private Team teamB;
    private User leaderB;
    private SavedCard cardB;
    private GameTeam sideA;
    private final List<GameTeam> activeTeams = new java.util.ArrayList<>();

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        gameTeamRepository = mock(GameTeamRepository.class);
        participantRepository = mock(GameParticipantRepository.class);
        bookingRepository = mock(BookingRepository.class);
        paymentService = mock(PaymentService.class);
        savedCardService = mock(SavedCardService.class);
        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.current()).thenReturn(
                PlatformSettings.builder().teamGamePlatformFee(FEE).build());

        service = new TeamGameService(gameRepository, gameTeamRepository, participantRepository,
                bookingRepository, paymentService, savedCardService, platformSettingsService,
                mock(NotificationService.class), mock(AuditService.class),
                new CancellationPolicy(), new AppProperties(),
                mock(TeamMemberRepository.class), mock(TeamRepository.class));

        User leaderA = User.builder().id(LEADER_A_ID).name("Leader A").build();
        leaderB = User.builder().id(LEADER_B_ID).name("Leader B").build();
        stadium = Stadium.builder().id(UUID.randomUUID()).name("Chilonzor Arena")
                .pricePerHour(PRICE).minBookingAmount(MINIMUM).cancellationHours(24)
                .owner(User.builder().id(UUID.randomUUID()).name("Owner").build()).build();
        teamA = Team.builder().id(TEAM_A_ID).name("Team A").leader(leaderA).build();
        teamB = Team.builder().id(TEAM_B_ID).name("Team B").leader(leaderB).build();
        cardB = SavedCard.builder().id(CARD_B_ID).user(leaderB).providerToken("tok-b").isActive(true).build();

        when(savedCardService.requireOwnedCard(LEADER_B_ID, CARD_B_ID)).thenReturn(cardB);
        when(gameTeamRepository.save(any(GameTeam.class))).thenAnswer(inv -> inv.getArgument(0));
        // A real repository makes a flushed row visible to the next query; the
        // paidAmount the booking ends up with depends on that.
        when(gameTeamRepository.saveAndFlush(any(GameTeam.class))).thenAnswer(inv -> {
            GameTeam entry = inv.getArgument(0);
            if (entry.getId() == null) {
                entry.setId(UUID.randomUUID());
            }
            activeTeams.add(entry);
            return entry;
        });
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            Booking booking = inv.getArgument(0);
            booking.setId(BOOKING_ID);
            return booking;
        });
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.findByGameIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(participantRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(paymentService.chargeForTeamGame(any(), any(), any())).thenAnswer(inv ->
                Payment.builder().id(UUID.randomUUID()).status(Payment.Status.PAID)
                        .providerTxnId("txn-charge-b").gameTeam(inv.getArgument(0)).build());
        }

    /** A waiting game whose kick-off is {@code until} away, with team A already in. */
    private Game waitingGame(Duration until) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plus(until);
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("open")
                .creator(teamA.getLeader()).stadium(stadium).teamA(teamA)
                .startsAt(start).endsAt(start.plusHours(1))
                .totalPrice(PRICE).maxPlayers(10).build();
        sideA = GameTeam.builder().id(UUID.randomUUID()).game(game).team(teamA).leader(teamA.getLeader())
                .side(Side.A).status(GameTeam.Status.ACTIVE)
                .feeAmount(FEE).advanceAmount(ADVANCE).remainderAmount(7_500_000L)
                .remainderStatus(GameTeam.RemainderStatus.UNPAID).build();
        activeTeams.clear();
        activeTeams.add(sideA);
        when(gameTeamRepository.findByGameIdAndStatus(GAME_ID, GameTeam.Status.ACTIVE))
                .thenAnswer(inv -> activeTeams.stream()
                        .filter(t -> t.getStatus() == GameTeam.Status.ACTIVE).toList());
        when(gameTeamRepository.findByGameIdAndSideAndStatus(GAME_ID, Side.A, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.of(sideA));
        when(gameTeamRepository.findByGameIdAndSideAndStatus(GAME_ID, Side.B, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.empty());
        return game;
    }

    private Game waitingGame() {
        return waitingGame(Duration.ofDays(2));
    }

    private Booking savedBooking() {
        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    @Test
    void joiningBooksTheSlotChargesTheJoinerAndCapturesTheHold() {
        Game game = waitingGame();

        service.join(game, teamB, leaderB, CARD_B_ID);

        InOrder order = inOrder(bookingRepository, paymentService);
        order.verify(bookingRepository).saveAndFlush(any(Booking.class));
        order.verify(paymentService).chargeForTeamGame(any(), eq(cardB), eq(Payment.PaymentType.TEAM_GAME));
        order.verify(paymentService).captureAllHeldTeamPayments(sideA.getId());

        assertThat(game.getStatus()).isEqualTo("confirmed");
        assertThat(game.getTeamB()).isSameAs(teamB);
        assertThat(game.getBookingId()).isEqualTo(BOOKING_ID);
        assertThat(savedBooking().getSource()).isEqualTo("game");
    }

    @Test
    void theBookingIsPartiallyPaidByBothAdvances() {
        service.join(waitingGame(), teamB, leaderB, CARD_B_ID);

        Booking booking = savedBooking();
        assertThat(booking.getPaidAmount()).isEqualTo(MINIMUM);   // advance A + advance B
        assertThat(booking.getPaymentStatus()).isEqualTo(PaymentStatus.PARTIALLY_PAID);
        assertThat(booking.getStatus()).isEqualTo("confirmed");
        assertThat(booking.getPrice()).isEqualTo(PRICE);
    }

    @Test
    void withoutAStadiumMinimumTheBookingIsPaidInFullOnConfirmation() {
        stadium.setMinBookingAmount(null);
        Game game = waitingGame();
        sideA.setAdvanceAmount(10_000_000L);
        sideA.setRemainderAmount(0L);

        service.join(game, teamB, leaderB, CARD_B_ID);

        assertThat(savedBooking().getPaidAmount()).isEqualTo(PRICE);
        assertThat(savedBooking().getPaymentStatus()).isEqualTo(PaymentStatus.PAID);
    }

    @Test
    void theJoiningTeamPaysTheSameFeeAndItsOwnHalfOfTheMinimum() {
        service.join(waitingGame(), teamB, leaderB, CARD_B_ID);

        ArgumentCaptor<GameTeam> captor = ArgumentCaptor.forClass(GameTeam.class);
        verify(gameTeamRepository).saveAndFlush(captor.capture());
        GameTeam sideB = captor.getValue();
        assertThat(sideB.getSide()).isEqualTo(Side.B);
        assertThat(sideB.getFeeAmount()).isEqualTo(FEE);
        assertThat(sideB.getAdvanceAmount()).isEqualTo(ADVANCE);
        assertThat(sideB.getRemainderAmount()).isEqualTo(7_500_000L);
    }

    @Test
    void joiningAfterTheThreeHourPointIs400() {
        assertThatThrownBy(() -> service.join(waitingGame(Duration.ofHours(2)), teamB, leaderB, CARD_B_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("O'yin boshlanishiga 3 soatdan kam qoldi, qo'shilib bo'lmaydi");

        verify(paymentService, never()).chargeForTeamGame(any(), any(), any());
        verify(bookingRepository, never()).saveAndFlush(any(Booking.class));
    }

    @Test
    void aTakenSlotCancelsTheGameRefundsTheWaitingTeamAndChargesNobody() {
        when(bookingRepository.saveAndFlush(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("bookings_no_overlap"));
        Game game = waitingGame();

        assertThatThrownBy(() -> service.join(game, teamB, leaderB, CARD_B_ID))
                .isInstanceOf(SlotAlreadyBookedException.class);

        assertThat(game.getStatus()).isEqualTo("cancelled_slot_taken");
        verify(paymentService).giveBackAllTeamPayments(eq(sideA.getId()), anyString());
        verify(paymentService, never()).chargeForTeamGame(any(), any(), any());
    }

    @Test
    void aDeclinedJoinerLeavesTheGameWaitingAndRemovesThePendingBooking() {
        when(paymentService.chargeForTeamGame(any(), any(), any())).thenAnswer(inv ->
                Payment.builder().id(UUID.randomUUID()).status(Payment.Status.FAILED)
                        .gameTeam(inv.getArgument(0)).build());
        Game game = waitingGame();

        assertThatThrownBy(() -> service.join(game, teamB, leaderB, CARD_B_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("declined");

        verify(bookingRepository).delete(any(Booking.class));
        verify(paymentService, never()).captureAllHeldTeamPayments(any());
        assertThat(game.getStatus()).isEqualTo("open");
        assertThat(game.getTeamB()).isNull();
    }

    @Test
    void aFailedCaptureRefundsTheJoinerAndCancelsEverything() {
        org.mockito.Mockito.doThrow(new BadRequestException("transaction gone"))
                .when(paymentService).captureAllHeldTeamPayments(any());
        Game game = waitingGame();

        assertThatThrownBy(() -> service.join(game, teamB, leaderB, CARD_B_ID))
                .isInstanceOf(BadRequestException.class);

        ArgumentCaptor<GameTeam> captor = ArgumentCaptor.forClass(GameTeam.class);
        verify(gameTeamRepository).saveAndFlush(captor.capture());
        verify(paymentService).giveBackAllTeamPayments(eq(captor.getValue().getId()), anyString());
        verify(bookingRepository).delete(any(Booking.class));
        assertThat(game.getStatus()).isEqualTo("cancelled_slot_taken");
    }

    @Test
    void secondOpponentLosingTheUniqueIndexIsNotCharged() {
        when(gameTeamRepository.saveAndFlush(any(GameTeam.class)))
                .thenThrow(new DataIntegrityViolationException("uq_game_teams_active_side"));

        assertThatThrownBy(() -> service.join(waitingGame(), teamB, leaderB, CARD_B_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("opponent");

        verify(paymentService, never()).chargeForTeamGame(any(), any(), any());
        verify(bookingRepository, never()).saveAndFlush(any(Booking.class));
    }

    @Test
    void onlyTheLeaderOfTheJoiningTeamMayJoin() {
        assertThatThrownBy(() -> service.join(waitingGame(), teamB, teamA.getLeader(), CARD_B_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aTeamCannotPlayAgainstItself() {
        assertThatThrownBy(() -> service.join(waitingGame(), teamA, teamA.getLeader(), CARD_B_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("against itself");
    }

    @Test
    void joiningNeedsATeamIdAndACardId() {
        assertThatThrownBy(() -> service.join(waitingGame(), null, leaderB, CARD_B_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("teamId");
        assertThatThrownBy(() -> service.join(waitingGame(), teamB, leaderB, null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cardId");
    }
}
