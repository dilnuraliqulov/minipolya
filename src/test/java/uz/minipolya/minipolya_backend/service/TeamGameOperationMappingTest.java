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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Every team-game cancellation rule maps to exactly one provider operation per payment:
 * <b>release</b> a hold (money back, no fee), <b>capture</b> a hold (money kept), or
 * <b>reverse</b> a captured or charged payment (money back in full). One test per row of
 * the table in CLAUDE.md ("Team game money → ATMOS operations"), each checking which
 * operation reached the provider.
 */
class TeamGameOperationMappingTest {

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
    private PaymentProvider paymentProvider;
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
        paymentProvider = mock(PaymentProvider.class);
        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.current()).thenReturn(
                PlatformSettings.builder().teamGamePlatformFee(FEE).build());

        // The provider always succeeds: what is being tested is our arithmetic,
        // not its failure handling.
        ProviderStubs.behaveLikeAtmos(paymentProvider);

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

        when(paymentRepository.save(any(Payment.class))).thenAnswer(ProviderStubs.savedWithId());
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

    private void onlyReleased(String holdId) {
        verify(paymentProvider).releaseHold(holdId);
        verify(paymentProvider, never()).captureHold(anyString());
        verify(paymentProvider, never()).reverse(anyString(), any());
    }

    // ---- a WAITING game: the creating team's money is a hold ----

    @Test
    void waitingSlotTakenReleasesTheHold() {
        Game game = waitingGame(Duration.ofDays(2));

        service.endWaitingGame(game, "cancelled_slot_taken", "Slot taken by another booking");

        onlyReleased("txn-a");
    }

    @Test
    void waitingThreeHourPointReleasesTheHold() {
        Game game = waitingGame(Duration.ofHours(2));

        service.endWaitingGame(game, "cancelled_not_filled", "No opponent was found in time");

        onlyReleased("txn-a");
        assertThat(game.getStatus()).isEqualTo("cancelled_not_filled");
    }

    @Test
    void waitingThreeHourPointOnAnAlreadyReleasedHoldStillEndsTheGame() {
        Game game = waitingGame(Duration.ofHours(2));
        when(paymentProvider.releaseHold("txn-a"))
                .thenThrow(new uz.minipolya.minipolya_backend.provider.HoldCancelledException("STPIMS-ERR-145"));

        service.endWaitingGame(game, "cancelled_not_filled", "No opponent was found in time");

        // Idempotent: a hold already let go counts as released.
        assertThat(ledger.getFirst().getStatus()).isEqualTo(Payment.Status.RELEASED);
        assertThat(game.getStatus()).isEqualTo("cancelled_not_filled");
    }

    @Test
    void waitingCancelBeforeTheDeadlineReleasesTheHold() {
        Game game = waitingGame(Duration.ofDays(2));

        service.cancelByLeader(game, LEADER_A_ID);

        onlyReleased("txn-a");
    }

    @Test
    void waitingCancelAfterTheDeadlineCapturesTheHold() {
        Game game = waitingGame(Duration.ofHours(10));   // past the 24 h deadline, before the 3-hour point

        service.cancelByLeader(game, LEADER_A_ID);

        verify(paymentProvider).captureHold("txn-a");
        verify(paymentProvider, never()).releaseHold(anyString());
        verify(paymentProvider, never()).reverse(anyString(), any());
        assertThat(ledger.getFirst().getStatus()).isEqualTo(Payment.Status.CAPTURED);
    }

    @Test
    void waitingCancelPastTheThreeHourPointReleasesTheHold() {
        Game game = waitingGame(Duration.ofHours(2));

        service.cancelByLeader(game, LEADER_A_ID);

        onlyReleased("txn-a");
    }

    // ---- a CONFIRMED game: both teams' money is taken (hold captured, joiner charged) ----

    @Test
    void confirmedCancelBeforeTheDeadlineReversesTheLeaver() {
        Game game = confirmedGame(Duration.ofDays(2));

        service.cancelByLeader(game, LEADER_B_ID);

        verify(paymentProvider).reverse(eq("txn-b"), anyString());
        verify(paymentProvider, never()).reverse(eq("txn-a"), anyString());
        verify(paymentProvider, never()).captureHold(anyString());
        verify(paymentProvider, never()).releaseHold(anyString());
    }

    @Test
    void confirmedCancelAfterTheDeadlineMovesNoMoney() {
        Game game = confirmedGame(Duration.ofHours(10));

        service.cancelByLeader(game, LEADER_B_ID);

        // The leaver's money is already taken and simply kept; the other team stays in.
        verify(paymentProvider, never()).reverse(anyString(), any());
        verify(paymentProvider, never()).releaseHold(anyString());
        verify(paymentProvider, never()).captureHold(anyString());
    }

    @Test
    void confirmedCancelPastTheThreeHourPointReversesTheTeamThatStayed() {
        Game game = confirmedGame(Duration.ofHours(2));

        service.cancelByLeader(game, LEADER_B_ID);

        verify(paymentProvider).reverse(eq("txn-a"), anyString());
        verify(paymentProvider, never()).reverse(eq("txn-b"), anyString());
    }

    @Test
    void ownerOrAdminCancellingTheBookingReversesBothTeams() {
        confirmedGame(Duration.ofHours(10));   // late, yet both get everything back

        service.cancelForBookingCancelled(GAME_ID, "The owner cancelled the booking");

        verify(paymentProvider).reverse(eq("txn-a"), anyString());
        verify(paymentProvider).reverse(eq("txn-b"), anyString());
    }

    @Test
    void aRemainderPaidByCardIsReversedWithTheRestOfTheLeaversMoney() {
        Game game = confirmedGame(Duration.ofDays(2));
        paidRemainder(sideB);

        service.cancelByLeader(game, LEADER_B_ID);

        verify(paymentProvider).reverse(eq("txn-b"), anyString());
        verify(paymentProvider).reverse(eq("txn-rem-b"), anyString());
    }
}
