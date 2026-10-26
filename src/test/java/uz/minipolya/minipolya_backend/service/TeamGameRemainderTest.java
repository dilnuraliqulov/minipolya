package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.response.GameTeamResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
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
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.repository.TeamMemberRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Each team settles its own remainder, by card or in cash, and the booking is
 * only fully paid once both of them have. Whichever lands first wins: the
 * other answers 400.
 */
class TeamGameRemainderTest {

    private static final long PRICE = 20_000_000L;       // 200 000 so'm
    private static final long FEE = 2_500_000L;
    private static final long ADVANCE = 2_500_000L;
    private static final long REMAINDER = 7_500_000L;    // 75 000 so'm each

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID LEADER_A_ID = UUID.randomUUID();
    private static final UUID LEADER_B_ID = UUID.randomUUID();
    private static final UUID CARD_B_ID = UUID.randomUUID();

    private GameTeamRepository gameTeamRepository;
    private BookingRepository bookingRepository;
    private PaymentService paymentService;
    private SavedCardService savedCardService;
    private TeamGameService service;

    private Game game;
    private GameTeam sideA;
    private GameTeam sideB;
    private Booking booking;
    private SavedCard cardB;
    private User owner;

    @BeforeEach
    void setUp() {
        GameRepository gameRepository = mock(GameRepository.class);
        gameTeamRepository = mock(GameTeamRepository.class);
        bookingRepository = mock(BookingRepository.class);
        paymentService = mock(PaymentService.class);
        savedCardService = mock(SavedCardService.class);
        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.current()).thenReturn(
                PlatformSettings.builder().teamGamePlatformFee(FEE).build());

        service = new TeamGameService(gameRepository, gameTeamRepository,
                mock(GameParticipantRepository.class), bookingRepository,
                paymentService, savedCardService, platformSettingsService,
                mock(NotificationService.class), mock(AuditService.class),
                new CancellationPolicy(), new AppProperties(),
                mock(TeamMemberRepository.class), mock(TeamRepository.class));

        owner = User.builder().id(OWNER_ID).name("Owner").build();
        User leaderA = User.builder().id(LEADER_A_ID).name("Leader A").build();
        User leaderB = User.builder().id(LEADER_B_ID).name("Leader B").build();
        Stadium stadium = Stadium.builder().id(UUID.randomUUID()).name("Chilonzor Arena")
                .cancellationHours(24).minBookingAmount(2 * ADVANCE).owner(owner).build();
        Team teamA = Team.builder().id(UUID.randomUUID()).name("Team A").leader(leaderA).build();
        Team teamB = Team.builder().id(UUID.randomUUID()).name("Team B").leader(leaderB).build();

        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
        game = Game.builder().id(GAME_ID).type("team_vs_team").status("confirmed")
                .creator(leaderA).stadium(stadium).teamA(teamA).teamB(teamB).bookingId(BOOKING_ID)
                .startsAt(start).endsAt(start.plusHours(1)).totalPrice(PRICE).maxPlayers(10).build();
        sideA = entry(teamA, Side.A);
        sideB = entry(teamB, Side.B);
        booking = Booking.builder().id(BOOKING_ID).stadium(stadium).user(leaderA)
                .source("game").status("confirmed").gameId(GAME_ID)
                .startsAt(start).endsAt(start.plusHours(1)).durationHours(1)
                .price(PRICE).paidAmount(2 * ADVANCE)
                .paymentStatus(Booking.PaymentStatus.PARTIALLY_PAID).build();
        cardB = SavedCard.builder().id(CARD_B_ID).user(leaderB).providerToken("tok-b").isActive(true).build();

        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(gameTeamRepository.findByGameIdAndStatus(GAME_ID, GameTeam.Status.ACTIVE))
                .thenAnswer(inv -> List.of(sideA, sideB));
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, LEADER_A_ID, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.of(sideA));
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, LEADER_B_ID, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.of(sideB));
        when(gameTeamRepository.findByGameIdAndSideAndStatus(GAME_ID, Side.A, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.of(sideA));
        when(gameTeamRepository.findByGameIdAndSideAndStatus(GAME_ID, Side.B, GameTeam.Status.ACTIVE))
                .thenReturn(Optional.of(sideB));
        when(gameTeamRepository.save(any(GameTeam.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(savedCardService.requireOwnedCard(LEADER_B_ID, CARD_B_ID)).thenReturn(cardB);
        when(paymentService.chargeForTeamGame(any(), any(), eq(Payment.PaymentType.REMAINDER)))
                .thenAnswer(inv -> Payment.builder().id(UUID.randomUUID()).status(Payment.Status.PAID)
                        .providerTxnId("txn-remainder").gameTeam(inv.getArgument(0)).build());
    }

    private GameTeam entry(Team team, Side side) {
        return GameTeam.builder().id(UUID.randomUUID()).team(team).leader(team.getLeader())
                .side(side).status(GameTeam.Status.ACTIVE)
                .feeAmount(FEE).advanceAmount(ADVANCE).remainderAmount(REMAINDER)
                .remainderStatus(GameTeam.RemainderStatus.UNPAID).build();
    }

    // ---- paying by card ----

    @Test
    void aLeaderPaysTheirOwnTeamsRemainderFromASavedCard() {
        service.payRemainder(GAME_ID, LEADER_B_ID, CARD_B_ID);

        verify(paymentService).chargeForTeamGame(sideB, cardB, Payment.PaymentType.REMAINDER);
        assertThat(sideB.getRemainderStatus()).isEqualTo(GameTeam.RemainderStatus.PAID);
        assertThat(sideA.getRemainderStatus()).isEqualTo(GameTeam.RemainderStatus.UNPAID);
    }

    @Test
    void oneTeamSettlingLeavesTheBookingStillPartiallyPaid() {
        service.payRemainder(GAME_ID, LEADER_B_ID, CARD_B_ID);

        assertThat(booking.getPaidAmount()).isEqualTo(2 * ADVANCE + REMAINDER);
        assertThat(booking.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.PARTIALLY_PAID);
    }

    @Test
    void theBookingIsPaidOnlyWhenBothActiveTeamsHaveSettled() {
        sideA.setRemainderStatus(GameTeam.RemainderStatus.PAID);

        service.payRemainder(GAME_ID, LEADER_B_ID, CARD_B_ID);

        assertThat(booking.getPaidAmount()).isEqualTo(PRICE);
        assertThat(booking.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.PAID);
    }

    @Test
    void payingATeamsRemainderTwiceIs400() {
        sideB.setRemainderStatus(GameTeam.RemainderStatus.PAID);

        assertThatThrownBy(() -> service.payRemainder(GAME_ID, LEADER_B_ID, CARD_B_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Already paid");
    }

    @Test
    void payRemainderWhenNothingIsOwedIs400() {
        sideB.setRemainderAmount(0L);   // the stadium had no minimum: the advance covered it

        assertThatThrownBy(() -> service.payRemainder(GAME_ID, LEADER_B_ID, CARD_B_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Already paid");
    }

    @Test
    void aDeclinedCardLeavesTheRemainderUnpaid() {
        when(paymentService.chargeForTeamGame(any(), any(), eq(Payment.PaymentType.REMAINDER)))
                .thenAnswer(inv -> Payment.builder().id(UUID.randomUUID()).status(Payment.Status.FAILED)
                        .gameTeam(inv.getArgument(0)).build());

        assertThatThrownBy(() -> service.payRemainder(GAME_ID, LEADER_B_ID, CARD_B_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("declined");

        assertThat(sideB.getRemainderStatus()).isEqualTo(GameTeam.RemainderStatus.UNPAID);
    }

    @Test
    void someoneWhoLeadsNoTeamInTheGameCannotPay() {
        assertThatThrownBy(() -> service.payRemainder(GAME_ID, UUID.randomUUID(), CARD_B_ID))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- the owner marks cash ----

    @Test
    void theOwnerMarksOneTeamsRemainderAsCash() {
        service.recordCashRemainder(GAME_ID, OWNER_ID, Side.A);

        verify(paymentService).recordCashTeamRemainder(sideA, owner);
        assertThat(sideA.getRemainderStatus()).isEqualTo(GameTeam.RemainderStatus.PAID);
        assertThat(sideB.getRemainderStatus()).isEqualTo(GameTeam.RemainderStatus.UNPAID);
    }

    @Test
    void cashAfterTheCardLosesWith400() {
        sideA.setRemainderStatus(GameTeam.RemainderStatus.PAID);

        assertThatThrownBy(() -> service.recordCashRemainder(GAME_ID, OWNER_ID, Side.A))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Already paid");

        verify(paymentService, never()).recordCashTeamRemainder(any(), any());
    }

    @Test
    void onlyTheStadiumOwnerCanMarkCash() {
        assertThatThrownBy(() -> service.recordCashRemainder(GAME_ID, LEADER_A_ID, Side.A))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- what the game response shows ----

    @Test
    void theGameShowsEachTeamsMoneySeparately() {
        sideA.setRemainderStatus(GameTeam.RemainderStatus.PAID);
        when(paymentService.refundableTeamTotal(any())).thenReturn(FEE + ADVANCE);

        List<GameTeamResponse> teams = service.teamsOf(game);

        assertThat(teams).hasSize(2);
        GameTeamResponse a = teams.stream().filter(t -> t.getSide() == Side.A).findFirst().orElseThrow();
        assertThat(a.getFeeAmount()).isEqualTo(FEE);
        assertThat(a.getAdvanceAmount()).isEqualTo(ADVANCE);
        assertThat(a.getRemainderAmount()).isEqualTo(REMAINDER);
        assertThat(a.getRemainderStatus()).isEqualTo("paid");
        assertThat(a.getTeamName()).isEqualTo("Team A");

        GameTeamResponse b = teams.stream().filter(t -> t.getSide() == Side.B).findFirst().orElseThrow();
        assertThat(b.getRemainderStatus()).isEqualTo("unpaid");
    }
}
