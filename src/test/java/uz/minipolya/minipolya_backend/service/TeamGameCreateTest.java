package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.CreateGameRequest;
import uz.minipolya.minipolya_backend.dto.response.GameDetailResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.mapper.GameMapper;
import uz.minipolya.minipolya_backend.mapper.GameParticipantMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Creating a team-vs-team game. The creating team's leader puts fee + advance
 * on hold and the game starts WAITING — no slot is booked, so the pitch stays
 * free for everyone until an opponent actually turns up.
 */
class TeamGameCreateTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private static final long PRICE = 20_000_000L;    // 200 000 so'm
    private static final long MINIMUM = 5_000_000L;   // 50 000 so'm
    private static final long FEE = 2_500_000L;       // 25 000 so'm

    private static final UUID LEADER_A_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID TEAM_A_ID = UUID.randomUUID();
    private static final UUID CARD_ID = UUID.randomUUID();

    private GameRepository gameRepository;
    private GameTeamRepository gameTeamRepository;
    private BookingRepository bookingRepository;
    private PaymentService paymentService;
    private SavedCardService savedCardService;
    private GameService service;

    private User leaderA;
    private Stadium stadium;
    private Team teamA;
    private SavedCard card;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        gameTeamRepository = mock(GameTeamRepository.class);
        bookingRepository = mock(BookingRepository.class);
        paymentService = mock(PaymentService.class);
        savedCardService = mock(SavedCardService.class);

        GameParticipantRepository participantRepository = mock(GameParticipantRepository.class);
        StadiumRepository stadiumRepository = mock(StadiumRepository.class);
        UserRepository userRepository = mock(UserRepository.class);
        TeamRepository teamRepository = mock(TeamRepository.class);
        BookingPriceCalculator priceCalculator = mock(BookingPriceCalculator.class);
        GameMapper gameMapper = mock(GameMapper.class);
        GameParticipantMapper participantMapper = mock(GameParticipantMapper.class);
        NotificationService notificationService = mock(NotificationService.class);
        AuditService auditService = mock(AuditService.class);
        StadiumClosureService closureService = mock(StadiumClosureService.class);
        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);

        leaderA = User.builder().id(LEADER_A_ID).name("Leader A").build();
        stadium = Stadium.builder().id(STADIUM_ID).name("Chilonzor Arena").status("approved").isActive(true)
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0))
                .pricePerHour(PRICE).minBookingAmount(MINIMUM).cancellationHours(24)
                .owner(User.builder().id(UUID.randomUUID()).name("Owner").build()).build();
        teamA = Team.builder().id(TEAM_A_ID).name("Team A").leader(leaderA).build();
        card = SavedCard.builder().id(CARD_ID).user(leaderA).providerToken("tok-a")
                .maskedNumber("8600 •••• 1234").isActive(true).build();

        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(LEADER_A_ID)).thenReturn(Optional.of(leaderA));
        when(teamRepository.findById(TEAM_A_ID)).thenReturn(Optional.of(teamA));
        when(priceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt())).thenReturn(PRICE);
        when(platformSettingsService.current()).thenReturn(
                PlatformSettings.builder().teamGamePlatformFee(FEE).casualGameServiceFee(0L).build());
        when(bookingRepository.existsActiveOverlap(any(), any(), any())).thenReturn(false);
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> {
            Game game = inv.getArgument(0);
            if (game.getId() == null) {
                game.setId(UUID.randomUUID());
            }
            return game;
        });
        when(gameTeamRepository.save(any(GameTeam.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameTeamRepository.saveAndFlush(any(GameTeam.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.findByGameId(any())).thenReturn(List.of());
        when(gameMapper.toDetailResponse(any(Game.class))).thenReturn(new GameDetailResponse());
        when(savedCardService.requireOwnedCard(LEADER_A_ID, CARD_ID)).thenReturn(card);
        when(paymentService.holdForTeamGame(any(), any())).thenAnswer(inv ->
                Payment.builder().id(UUID.randomUUID()).status(Payment.Status.HELD)
                        .providerTxnId("txn-hold-a").gameTeam(inv.getArgument(0)).build());

        TeamGameService teamGameService = new TeamGameService(gameRepository, gameTeamRepository,
                participantRepository, bookingRepository, paymentService, savedCardService,
                platformSettingsService, notificationService, auditService,
                new uz.minipolya.minipolya_backend.util.CancellationPolicy(), new AppProperties());

        service = new GameService(gameRepository, participantRepository, stadiumRepository, userRepository,
                teamRepository, priceCalculator, bookingRepository, gameMapper, participantMapper,
                new AppProperties(), paymentService, notificationService, auditService,
                new BookingWindowValidator(closureService), platformSettingsService, teamGameService);
    }

    private CreateGameRequest request(UUID cardId) {
        return CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("team_vs_team").title("Derby")
                .maxPlayers(10).duration(1).startsAt(startAt(20, 3))
                .teamAId(TEAM_A_ID).cardId(cardId)
                .build();
    }

    private OffsetDateTime startAt(int tashkentHour, long daysAhead) {
        return LocalDate.now(TASHKENT).plusDays(daysAhead).atTime(tashkentHour, 0)
                .atZone(TASHKENT).toOffsetDateTime();
    }

    private GameTeam savedGameTeam() {
        ArgumentCaptor<GameTeam> captor = ArgumentCaptor.forClass(GameTeam.class);
        verify(gameTeamRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private Game savedGame() {
        ArgumentCaptor<Game> captor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void creatingATeamGameHoldsFeePlusAdvanceAndLeavesTheSlotFree() {
        service.createGame(request(CARD_ID), LEADER_A_ID);

        GameTeam sideA = savedGameTeam();
        assertThat(sideA.getSide()).isEqualTo(Side.A);
        assertThat(sideA.getFeeAmount()).isEqualTo(FEE);
        assertThat(sideA.getAdvanceAmount()).isEqualTo(2_500_000L);      // M / 2
        assertThat(sideA.getRemainderAmount()).isEqualTo(7_500_000L);    // (P - M) / 2
        verify(paymentService).holdForTeamGame(sideA, card);
        verify(bookingRepository, never()).saveAndFlush(any(Booking.class));
        assertThat(savedGame().getStatus()).isEqualTo("open");
    }

    @Test
    void theCreatingTeamIsRecordedAsActiveOnSideA() {
        service.createGame(request(CARD_ID), LEADER_A_ID);

        GameTeam sideA = savedGameTeam();
        assertThat(sideA.getStatus()).isEqualTo(GameTeam.Status.ACTIVE);
        assertThat(sideA.getTeam()).isSameAs(teamA);
        assertThat(sideA.getLeader()).isSameAs(leaderA);
        assertThat(sideA.getRemainderStatus()).isEqualTo(GameTeam.RemainderStatus.UNPAID);
    }

    @Test
    void aDeclinedCardMeansNoGame() {
        when(paymentService.holdForTeamGame(any(), any())).thenAnswer(inv ->
                Payment.builder().id(UUID.randomUUID()).status(Payment.Status.FAILED)
                        .gameTeam(inv.getArgument(0)).build());

        assertThatThrownBy(() -> service.createGame(request(CARD_ID), LEADER_A_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("declined");
    }

    @Test
    void aTeamGameNeedsACardId() {
        assertThatThrownBy(() -> service.createGame(request(null), LEADER_A_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("cardId");

        verify(paymentService, never()).holdForTeamGame(any(), any());
    }

    @Test
    void withoutAStadiumMinimumTheAdvanceIsHalfThePitchPrice() {
        stadium.setMinBookingAmount(null);

        service.createGame(request(CARD_ID), LEADER_A_ID);

        GameTeam sideA = savedGameTeam();
        assertThat(sideA.getAdvanceAmount()).isEqualTo(10_000_000L);
        assertThat(sideA.getRemainderAmount()).isZero();
    }
}
