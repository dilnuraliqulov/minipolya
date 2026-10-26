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
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.ClickProvider;
import uz.minipolya.minipolya_backend.provider.PaymeProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.ProviderTransaction;
import uz.minipolya.minipolya_backend.provider.ProviderTransactionStatus;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.repository.TeamMemberRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code refundIfCancelledNow} has to be the truth, not an estimate: whatever
 * it promises a team is exactly what a real cancellation at that moment gives
 * back. These tests run both — the figure and then the cancellation — against
 * the same payment rows and compare.
 *
 * <p>A real {@link PaymentService} over a stubbed provider does the money, so
 * the amounts are the ones the rows actually end up with.
 */
class TeamGameRefundPreviewTest {

    private static final long FEE = 2_500_000L;        // 25 000 so'm
    private static final long ADVANCE = 2_500_000L;    // 25 000 so'm
    private static final long REMAINDER = 7_500_000L;  // 75 000 so'm
    private static final long PRICE = 20_000_000L;

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID LEADER_A_ID = UUID.randomUUID();
    private static final UUID LEADER_B_ID = UUID.randomUUID();

    /** Statuses that mean the money has gone back to the cardholder. */
    private static final EnumSet<Payment.Status> GIVEN_BACK =
            EnumSet.of(Payment.Status.RELEASED, Payment.Status.REFUNDED);

    private GameRepository gameRepository;
    private GameTeamRepository gameTeamRepository;
    private PaymentRepository paymentRepository;
    private PaymentService paymentService;
    private TeamGameService service;

    private Stadium stadium;
    private Team teamA;
    private Team teamB;
    private GameTeam sideA;
    private GameTeam sideB;
    private final List<Payment> ledger = new ArrayList<>();

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        gameTeamRepository = mock(GameTeamRepository.class);
        BookingRepository bookingRepository = mock(BookingRepository.class);
        paymentRepository = mock(PaymentRepository.class);
        PaymentProvider paymentProvider = mock(PaymentProvider.class);
        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.current()).thenReturn(
                PlatformSettings.builder().teamGamePlatformFee(FEE).build());

        // The provider always succeeds: what is being tested is our arithmetic,
        // not its failure handling.
        when(paymentProvider.release(anyString())).thenAnswer(inv ->
                new ProviderTransaction(inv.getArgument(0), ProviderTransactionStatus.RELEASED));
        when(paymentProvider.refund(anyString())).thenAnswer(inv ->
                new ProviderTransaction(inv.getArgument(0), ProviderTransactionStatus.REFUNDED));
        when(paymentProvider.capture(anyString())).thenAnswer(inv ->
                new ProviderTransaction(inv.getArgument(0), ProviderTransactionStatus.CAPTURED));

        paymentService = new PaymentService(paymentRepository, bookingRepository, mock(UserRepository.class),
                gameRepository, mock(GameParticipantRepository.class), mock(PaymentMapper.class),
                List.of(new PaymeProvider(), new ClickProvider()), mock(GameService.class),
                mock(NotificationService.class), mock(AuditService.class),
                paymentProvider, platformSettingsService);

        service = new TeamGameService(gameRepository, gameTeamRepository,
                mock(GameParticipantRepository.class), bookingRepository,
                paymentService, mock(SavedCardService.class), platformSettingsService,
                mock(NotificationService.class), mock(AuditService.class),
                new CancellationPolicy(), new AppProperties(),
                mock(TeamMemberRepository.class), mock(TeamRepository.class));

        User owner = User.builder().id(UUID.randomUUID()).name("Owner").build();
        User leaderA = User.builder().id(LEADER_A_ID).name("Leader A").build();
        User leaderB = User.builder().id(LEADER_B_ID).name("Leader B").build();
        stadium = Stadium.builder().id(UUID.randomUUID()).name("Chilonzor Arena")
                .cancellationHours(24).minBookingAmount(2 * ADVANCE).owner(owner).build();
        teamA = Team.builder().id(UUID.randomUUID()).name("Team A").leader(leaderA).build();
        teamB = Team.builder().id(UUID.randomUUID()).name("Team B").leader(leaderB).build();

        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameTeamRepository.save(any(GameTeam.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    /** A confirmed game with both teams in and their joining money captured. */
    private Game confirmedGame(Duration until) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plus(until);
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("confirmed")
                .creator(teamA.getLeader()).stadium(stadium).teamA(teamA).teamB(teamB)
                .bookingId(BOOKING_ID).startsAt(start).endsAt(start.plusHours(1))
                .totalPrice(PRICE).maxPlayers(10).build();
        sideA = entry(game, teamA, Side.A);
        sideB = entry(game, teamB, Side.B);
        wire(game);
        joined(sideA, Payment.Status.CAPTURED);
        joined(sideB, Payment.Status.CAPTURED);
        return game;
    }

    /** A waiting game with only team A in, its money still on hold. */
    private Game waitingGame(Duration until) {
        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plus(until);
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("open")
                .creator(teamA.getLeader()).stadium(stadium).teamA(teamA)
                .startsAt(start).endsAt(start.plusHours(1))
                .totalPrice(PRICE).maxPlayers(10).build();
        sideA = entry(game, teamA, Side.A);
        sideB = null;
        wire(game);
        joined(sideA, Payment.Status.HELD);
        return game;
    }

    private GameTeam entry(Game game, Team team, Side side) {
        return GameTeam.builder().id(UUID.randomUUID()).game(game).team(team).leader(team.getLeader())
                .side(side).status(GameTeam.Status.ACTIVE)
                .feeAmount(FEE).advanceAmount(ADVANCE).remainderAmount(REMAINDER)
                .remainderStatus(GameTeam.RemainderStatus.UNPAID).build();
    }

    private void wire(Game game) {
        when(gameTeamRepository.findByGameIdAndStatus(GAME_ID, GameTeam.Status.ACTIVE))
                .thenAnswer(inv -> activeTeams());
        when(gameTeamRepository.findByGameId(GAME_ID)).thenAnswer(inv -> allTeams());
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, LEADER_A_ID, GameTeam.Status.ACTIVE))
                .thenAnswer(inv -> sideA != null && sideA.getStatus() == GameTeam.Status.ACTIVE
                        ? Optional.of(sideA) : Optional.empty());
        when(gameTeamRepository.findByGameIdAndLeaderIdAndStatus(GAME_ID, LEADER_B_ID, GameTeam.Status.ACTIVE))
                .thenAnswer(inv -> sideB != null && sideB.getStatus() == GameTeam.Status.ACTIVE
                        ? Optional.of(sideB) : Optional.empty());
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
    }

    private List<GameTeam> activeTeams() {
        return allTeams().stream().filter(t -> t.getStatus() == GameTeam.Status.ACTIVE).toList();
    }

    private List<GameTeam> allTeams() {
        List<GameTeam> all = new ArrayList<>();
        if (sideA != null) {
            all.add(sideA);
        }
        if (sideB != null) {
            all.add(sideB);
        }
        return all;
    }

    /** Records this team's joining payment at the given status. */
    private void joined(GameTeam team, Payment.Status status) {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID()).game(team.getGame()).gameTeam(team).user(team.getLeader())
                .amount(FEE + ADVANCE).ownerAmount(ADVANCE).platformAmount(FEE).commissionPercent(0)
                .method(Payment.Method.CARD).paymentType(Payment.PaymentType.TEAM_GAME)
                .provider(Payment.Provider.ATMOS).providerTxnId("txn-" + team.getSide().getValue())
                .status(status).build();
        ledger.add(payment);
        stubLedger();
    }

    /** Records a remainder this team paid in the app. */
    private void paidRemainder(GameTeam team) {
        Payment payment = Payment.builder()
                .id(UUID.randomUUID()).game(team.getGame()).gameTeam(team).user(team.getLeader())
                .amount(REMAINDER).ownerAmount(REMAINDER).platformAmount(0L).commissionPercent(0)
                .method(Payment.Method.CARD).paymentType(Payment.PaymentType.REMAINDER)
                .provider(Payment.Provider.ATMOS).providerTxnId("txn-rem-" + team.getSide().getValue())
                .status(Payment.Status.PAID).build();
        team.setRemainderStatus(GameTeam.RemainderStatus.PAID);
        ledger.add(payment);
        stubLedger();
    }

    private void stubLedger() {
        for (GameTeam team : allTeams()) {
            UUID id = team.getId();
            when(paymentRepository.findByGameTeamId(id)).thenAnswer(inv -> ledger.stream()
                    .filter(p -> p.getGameTeam() != null && id.equals(p.getGameTeam().getId()))
                    .toList());
        }
    }

    /** What actually went back to this team, read off the rows after the fact. */
    private long actuallyGivenBack(GameTeam team) {
        return ledger.stream()
                .filter(p -> p.getGameTeam() != null && p.getGameTeam().getId().equals(team.getId()))
                .filter(p -> GIVEN_BACK.contains(p.getStatus()))
                .mapToLong(Payment::getAmount)
                .sum();
    }

    @Test
    void previewMatchesAnEarlyCancellation() {
        Game game = confirmedGame(Duration.ofDays(2));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        long preview = service.refundIfCancelledNow(game, sideB, now);
        service.cancelByLeader(game, LEADER_B_ID);

        assertThat(preview).isEqualTo(FEE + ADVANCE);
        assertThat(actuallyGivenBack(sideB)).isEqualTo(preview);
    }

    @Test
    void previewMatchesAnEarlyCancellationWithARemainderAlreadyPaid() {
        Game game = confirmedGame(Duration.ofDays(2));
        paidRemainder(sideB);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        long preview = service.refundIfCancelledNow(game, sideB, now);
        service.cancelByLeader(game, LEADER_B_ID);

        assertThat(preview).isEqualTo(FEE + ADVANCE + REMAINDER);
        assertThat(actuallyGivenBack(sideB)).isEqualTo(preview);
    }

    @Test
    void previewMatchesALateCancellation() {
        Game game = confirmedGame(Duration.ofHours(10));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        long preview = service.refundIfCancelledNow(game, sideB, now);
        service.cancelByLeader(game, LEADER_B_ID);

        assertThat(preview).isZero();
        assertThat(actuallyGivenBack(sideB)).isZero();
    }

    @Test
    void previewMatchesACancellationPastTheThreeHourPoint() {
        Game game = confirmedGame(Duration.ofHours(2));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        long cancellerPreview = service.refundIfCancelledNow(game, sideB, now);
        long stayerPreview = service.refundIfCancelledNow(game, sideA, now);
        service.cancelByLeader(game, LEADER_B_ID);

        assertThat(cancellerPreview).isZero();
        assertThat(actuallyGivenBack(sideB)).isZero();
        // The team that stayed is left without an opponent it cannot replace,
        // so it is made whole — whatever its own preview said about cancelling.
        assertThat(stayerPreview).isZero();
        assertThat(actuallyGivenBack(sideA)).isEqualTo(FEE + ADVANCE);
    }

    @Test
    void previewForALoneWaitingTeamPastTheThreeHourPointMatchesTheAutoCancel() {
        Game game = waitingGame(Duration.ofHours(2));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        long preview = service.refundIfCancelledNow(game, sideA, now);
        service.cancelByLeader(game, LEADER_A_ID);

        assertThat(preview).isEqualTo(FEE + ADVANCE);
        assertThat(actuallyGivenBack(sideA)).isEqualTo(preview);
    }

    @Test
    void previewMatchesAWaitingTeamCancellingInTime() {
        Game game = waitingGame(Duration.ofDays(2));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        long preview = service.refundIfCancelledNow(game, sideA, now);
        service.cancelByLeader(game, LEADER_A_ID);

        assertThat(preview).isEqualTo(FEE + ADVANCE);
        assertThat(actuallyGivenBack(sideA)).isEqualTo(preview);
    }

    @Test
    void previewMatchesAWaitingTeamCancellingTooLate() {
        Game game = waitingGame(Duration.ofHours(10));
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        long preview = service.refundIfCancelledNow(game, sideA, now);
        service.cancelByLeader(game, LEADER_A_ID);

        assertThat(preview).isZero();
        assertThat(actuallyGivenBack(sideA)).isZero();
    }

    @Test
    void previewIsZeroOnceThatTeamHasAlreadyBeenRefunded() {
        Game game = confirmedGame(Duration.ofDays(2));
        service.cancelByLeader(game, LEADER_B_ID);

        assertThat(service.refundIfCancelledNow(game, sideB, OffsetDateTime.now(ZoneOffset.UTC))).isZero();
    }

    @Test
    void theResponseCarriesTheDeadlineAndTheSameFigure() {
        Game game = confirmedGame(Duration.ofDays(2));

        List<GameTeamResponse> teams = service.teamsOf(game);

        GameTeamResponse b = teams.stream().filter(t -> t.getSide() == Side.B).findFirst().orElseThrow();
        assertThat(b.getCancellationDeadline()).isEqualTo(game.getStartsAt().minusHours(24));
        assertThat(b.getRefundIfCancelledNow()).isEqualTo(FEE + ADVANCE);
    }
}
