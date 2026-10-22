package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.CreateGameRequest;
import uz.minipolya.minipolya_backend.dto.response.GameDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.GameParticipantResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.mapper.GameMapper;
import uz.minipolya.minipolya_backend.mapper.GameParticipantMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GameServiceTest {

    private GameRepository gameRepository;
    private GameParticipantRepository participantRepository;
    private StadiumRepository stadiumRepository;
    private UserRepository userRepository;
    private TeamRepository teamRepository;
    private PricingRuleRepository pricingRuleRepository;
    private PriceCalculator priceCalculator;
    private BookingRepository bookingRepository;
    private GameMapper gameMapper;
    private GameParticipantMapper participantMapper;
    private PaymentService paymentService;
    private NotificationService notificationService;
    private AuditService auditService;
    private GameService service;

    private static final UUID CREATOR_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID TEAM_A_ID = UUID.randomUUID();
    private static final UUID JOINER_ID = UUID.randomUUID();
    private static final UUID LEADER_B_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID PAYMENT_ID = UUID.randomUUID();

    private User creator;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        gameRepository = mock(GameRepository.class);
        participantRepository = mock(GameParticipantRepository.class);
        stadiumRepository = mock(StadiumRepository.class);
        userRepository = mock(UserRepository.class);
        teamRepository = mock(TeamRepository.class);
        pricingRuleRepository = mock(PricingRuleRepository.class);
        priceCalculator = mock(PriceCalculator.class);
        bookingRepository = mock(BookingRepository.class);
        gameMapper = mock(GameMapper.class);
        participantMapper = mock(GameParticipantMapper.class);
        paymentService = mock(PaymentService.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);

        service = new GameService(gameRepository, participantRepository, stadiumRepository, userRepository,
                teamRepository, pricingRuleRepository, priceCalculator, bookingRepository,
                gameMapper, participantMapper, new AppProperties(), paymentService, notificationService, auditService);

        creator = User.builder().id(CREATOR_ID).name("Creator").build();
        stadium = Stadium.builder().id(STADIUM_ID).status("approved").isActive(true).pricePerHour(50_000).build();
    }

    private OffsetDateTime inHours(long h) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(h);
    }

    private void stubCreate() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(CREATOR_ID)).thenReturn(Optional.of(creator));
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(100_000);
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> {
            Game g = inv.getArgument(0);
            if (g.getId() == null) {
                g.setId(GAME_ID);
            }
            return g;
        });
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameMapper.toDetailResponse(any(Game.class))).thenReturn(new GameDetailResponse());
        when(participantRepository.findByGameId(GAME_ID)).thenReturn(List.of());
    }

    // ---- invite link ----

    @Test
    void inviteLinkIsOmittedWhenBotUsernameIsNotConfigured() {
        stubCreate(); // AppProperties default: app.bot.username is null
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10).startsAt(inHours(24)).duration(1).build();

        GameDetailResponse detail = service.createGame(req, CREATOR_ID);

        assertThat(detail.getInviteLink()).isNull();
    }

    @Test
    void inviteLinkUsesTheConfiguredBotUsername() {
        AppProperties props = new AppProperties();
        props.getBot().setUsername("minipolya_bot");
        service = new GameService(gameRepository, participantRepository, stadiumRepository, userRepository,
                teamRepository, pricingRuleRepository, priceCalculator, bookingRepository,
                gameMapper, participantMapper, props, paymentService, notificationService, auditService);
        stubCreate();
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10).startsAt(inHours(24)).duration(1).build();

        GameDetailResponse detail = service.createGame(req, CREATOR_ID);

        assertThat(detail.getInviteLink()).isEqualTo("t.me/minipolya_bot?start=game_" + GAME_ID);
    }

    // ---- createGame ----

    @Test
    void createCasualGameAddsCreatorAsParticipant() {
        stubCreate();
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10).startsAt(inHours(24)).duration(1).build();

        service.createGame(req, CREATOR_ID);

        ArgumentCaptor<Game> gameCaptor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(gameCaptor.capture());
        Game game = gameCaptor.getValue();
        assertThat(game.getType()).isEqualTo("casual");
        assertThat(game.getStatus()).isEqualTo("open");
        assertThat(game.isPublic()).isFalse();
        assertThat(game.getTotalPrice()).isEqualTo(100_000);

        ArgumentCaptor<GameParticipant> pCaptor = ArgumentCaptor.forClass(GameParticipant.class);
        verify(participantRepository).save(pCaptor.capture());
        GameParticipant participant = pCaptor.getValue();
        assertThat(participant.getUser()).isSameAs(creator);
        assertThat(participant.getSide()).isNull();
        assertThat(participant.getStatus()).isEqualTo(GameParticipant.Status.JOINED);
    }

    @Test
    void createOpenGameIsPublic() {
        stubCreate();
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("open").maxPlayers(10).startsAt(inHours(24)).duration(1).build();

        service.createGame(req, CREATOR_ID);

        ArgumentCaptor<Game> gameCaptor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(gameCaptor.capture());
        assertThat(gameCaptor.getValue().isPublic()).isTrue();
    }

    @Test
    void createTeamVsTeamPutsCreatorOnSideA() {
        stubCreate();
        Team teamA = Team.builder().id(TEAM_A_ID).name("A").leader(creator).build();
        when(teamRepository.findById(TEAM_A_ID)).thenReturn(Optional.of(teamA));
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("team_vs_team").maxPlayers(2).startsAt(inHours(24)).duration(1).teamAId(TEAM_A_ID).build();

        service.createGame(req, CREATOR_ID);

        ArgumentCaptor<Game> gameCaptor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(gameCaptor.capture());
        assertThat(gameCaptor.getValue().getTeamA()).isSameAs(teamA);

        ArgumentCaptor<GameParticipant> pCaptor = ArgumentCaptor.forClass(GameParticipant.class);
        verify(participantRepository).save(pCaptor.capture());
        assertThat(pCaptor.getValue().getSide()).isEqualTo(GameParticipant.Side.A);
    }

    // ---- joinGame ----

    @Test
    void joinOpenGameAddsParticipant() {
        Game game = Game.builder().id(GAME_ID).type("open").status("open").maxPlayers(10).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, JOINER_ID)).thenReturn(Optional.empty());
        when(participantRepository.findByGameId(GAME_ID)).thenReturn(List.of());
        when(userRepository.findById(JOINER_ID)).thenReturn(Optional.of(User.builder().id(JOINER_ID).name("Joiner").build()));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantMapper.toResponse(any())).thenReturn(new GameParticipantResponse());

        service.joinGame(GAME_ID, JOINER_ID, null);

        verify(participantRepository).save(any(GameParticipant.class));
    }

    @Test
    void joinFullGameIsRejected() {
        Game game = Game.builder().id(GAME_ID).type("open").status("open").maxPlayers(1).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, JOINER_ID)).thenReturn(Optional.empty());
        when(participantRepository.findByGameId(GAME_ID)).thenReturn(List.of(
                GameParticipant.builder().id(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> service.joinGame(GAME_ID, JOINER_ID, null)).isInstanceOf(BadRequestException.class);
        verify(participantRepository, never()).save(any());
    }

    @Test
    void joinGameAlreadyInIsRejected() {
        Game game = Game.builder().id(GAME_ID).type("open").status("open").maxPlayers(10).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, JOINER_ID))
                .thenReturn(Optional.of(GameParticipant.builder().id(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> service.joinGame(GAME_ID, JOINER_ID, null)).isInstanceOf(BadRequestException.class);
        verify(participantRepository, never()).save(any());
    }

    // ---- handleGamePaymentConfirmed ----

    @Test
    void paymentConfirmedMarksParticipantPaidWithoutConfirmingIfNotFunded() {
        Game game = Game.builder().id(GAME_ID).type("casual").status("open").maxPlayers(4).build();
        GameParticipant participant = GameParticipant.builder().id(UUID.randomUUID()).game(game).user(creator)
                .status(GameParticipant.Status.JOINED).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, CREATOR_ID)).thenReturn(Optional.of(participant));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.countByGameIdAndStatus(GAME_ID, GameParticipant.Status.PAID)).thenReturn(1);

        service.handleGamePaymentConfirmed(GAME_ID, CREATOR_ID);

        assertThat(participant.getStatus()).isEqualTo(GameParticipant.Status.PAID);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void allPlayersPaidCreatesBookingAndConfirmsCasualGame() {
        OffsetDateTime start = inHours(24);
        Game game = Game.builder().id(GAME_ID).type("casual").status("open").maxPlayers(2)
                .creator(creator).stadium(stadium).startsAt(start).endsAt(start.plusHours(1)).totalPrice(100_000).build();
        GameParticipant participant = GameParticipant.builder().id(UUID.randomUUID()).game(game).user(creator)
                .status(GameParticipant.Status.JOINED).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, CREATOR_ID)).thenReturn(Optional.of(participant));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.countByGameIdAndStatus(GAME_ID, GameParticipant.Status.PAID)).thenReturn(2);
        when(participantRepository.findByGameId(GAME_ID)).thenReturn(List.of(participant));
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(BOOKING_ID);
            return b;
        });
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        service.handleGamePaymentConfirmed(GAME_ID, CREATOR_ID);

        assertThat(game.getStatus()).isEqualTo("confirmed");
        assertThat(game.getBookingId()).isEqualTo(BOOKING_ID);
        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(bookingCaptor.capture());
        assertThat(bookingCaptor.getValue().getSource()).isEqualTo("game");
        assertThat(bookingCaptor.getValue().getStatus()).isEqualTo("confirmed");
    }

    @Test
    void bothLeadersPaidConfirmsTeamVsTeamGame() {
        OffsetDateTime start = inHours(24);
        User leaderB = User.builder().id(LEADER_B_ID).name("Leader B").build();
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("open").creatorSharePct(50)
                .creator(creator).stadium(stadium).startsAt(start).endsAt(start.plusHours(1)).totalPrice(100_000).build();
        GameParticipant leaderBPart = GameParticipant.builder().id(UUID.randomUUID()).game(game).user(leaderB)
                .side(GameParticipant.Side.B).status(GameParticipant.Status.JOINED).build();
        GameParticipant leaderAPart = GameParticipant.builder().id(UUID.randomUUID()).game(game).user(creator)
                .side(GameParticipant.Side.A).status(GameParticipant.Status.PAID).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, LEADER_B_ID)).thenReturn(Optional.of(leaderBPart));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.findByGameIdAndSide(GAME_ID, GameParticipant.Side.A)).thenReturn(List.of(leaderAPart));
        when(participantRepository.findByGameIdAndSide(GAME_ID, GameParticipant.Side.B)).thenReturn(List.of(leaderBPart));
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(BOOKING_ID);
            return b;
        });
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        service.handleGamePaymentConfirmed(GAME_ID, LEADER_B_ID);

        assertThat(leaderBPart.getStatus()).isEqualTo(GameParticipant.Status.PAID);
        assertThat(game.getStatus()).isEqualTo("confirmed");
        assertThat(game.getBookingId()).isEqualTo(BOOKING_ID);
    }

    // ---- cancelGamesOnSlot ----

    @Test
    void cancelGamesOnSlotCancelsAndRefundsPaidParticipants() {
        OffsetDateTime start = inHours(24);
        Game game = Game.builder().id(GAME_ID).status("open").stadium(stadium)
                .startsAt(start).endsAt(start.plusHours(1)).build();
        Payment payment = Payment.builder().id(PAYMENT_ID).build();
        GameParticipant paid = GameParticipant.builder().id(UUID.randomUUID()).game(game).user(creator)
                .status(GameParticipant.Status.PAID).payment(payment).build();
        when(gameRepository.findByStadiumIdAndStatus(STADIUM_ID, "open")).thenReturn(List.of(game));
        when(participantRepository.findByGameId(GAME_ID)).thenReturn(List.of(paid));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancelGamesOnSlot(STADIUM_ID, start, start.plusHours(2));

        assertThat(game.getStatus()).isEqualTo("cancelled_slot_taken");
        assertThat(paid.getStatus()).isEqualTo(GameParticipant.Status.REFUNDED);
        verify(paymentService).refundFullPayment(PAYMENT_ID, "Slot taken by another booking");
    }

    // ---- cancelUnfilledGames (GameTimeoutJob) ----

    @Test
    void cancelUnfilledGamesCancelsExpiredOpenGames() {
        OffsetDateTime pastStart = inHours(-1);
        Game game = Game.builder().id(GAME_ID).status("open").stadium(stadium)
                .startsAt(pastStart).endsAt(pastStart.plusHours(1)).build();
        Payment payment = Payment.builder().id(PAYMENT_ID).build();
        GameParticipant paid = GameParticipant.builder().id(UUID.randomUUID()).game(game).user(creator)
                .status(GameParticipant.Status.PAID).payment(payment).build();
        when(gameRepository.findByStatusAndStartsAtBefore(eq("open"), any(OffsetDateTime.class)))
                .thenReturn(List.of(game));
        when(participantRepository.findByGameId(GAME_ID)).thenReturn(List.of(paid));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = service.cancelUnfilledGames();

        assertThat(count).isEqualTo(1);
        assertThat(game.getStatus()).isEqualTo("cancelled_not_filled");
        assertThat(paid.getStatus()).isEqualTo(GameParticipant.Status.REFUNDED);
        verify(paymentService).refundFullPayment(PAYMENT_ID, "Game did not fill in time");
    }
}
