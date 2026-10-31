package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditActorType;
import uz.minipolya.minipolya_backend.audit.AuditChange;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.Audited;
import uz.minipolya.minipolya_backend.dto.request.CreateGameRequest;
import uz.minipolya.minipolya_backend.dto.response.GameDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.GameParticipantResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.GameMapper;
import uz.minipolya.minipolya_backend.mapper.GameParticipantMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
    private BookingPriceCalculator bookingPriceCalculator;
    private BookingRepository bookingRepository;
    private GameMapper gameMapper;
    private GameParticipantMapper participantMapper;
    private PaymentService paymentService;
    private NotificationService notificationService;
    private AuditService auditService;
    private StadiumClosureService closureService;
    private PlatformSettingsService platformSettingsService;
    private TeamGameService teamGameService;
    private GameService service;

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private static final UUID CREATOR_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID TEAM_A_ID = UUID.randomUUID();
    private static final UUID TEAM_B_ID = UUID.randomUUID();
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
        bookingPriceCalculator = mock(BookingPriceCalculator.class);
        bookingRepository = mock(BookingRepository.class);
        gameMapper = mock(GameMapper.class);
        participantMapper = mock(GameParticipantMapper.class);
        paymentService = mock(PaymentService.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        closureService = mock(StadiumClosureService.class);
        platformSettingsService = mock(PlatformSettingsService.class);
        teamGameService = mock(TeamGameService.class);
        when(platformSettingsService.current()).thenReturn(PlatformSettings.builder().build());

        service = new GameService(gameRepository, participantRepository, stadiumRepository, userRepository,
                teamRepository, bookingPriceCalculator, bookingRepository,
                gameMapper, participantMapper, new AppProperties(), paymentService, notificationService,
                auditService, new BookingWindowValidator(closureService), platformSettingsService, teamGameService,
                mock(SavedCardService.class));

        creator = User.builder().id(CREATOR_ID).name("Creator").build();
        stadium = Stadium.builder().id(STADIUM_ID).status("approved").isActive(true).pricePerHour(50_000L)
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0)).build();
    }

    private OffsetDateTime inHours(long h) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(h);
    }

    /** A future slot on the hour, inside the stadium's 08:00-23:00 Tashkent window. */
    private OffsetDateTime startAt(int localHour, int daysAhead) {
        return LocalDate.now(TASHKENT).plusDays(daysAhead).atTime(localHour, 0)
                .atZone(TASHKENT).toOffsetDateTime();
    }

    private void stubCreate() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(CREATOR_ID)).thenReturn(Optional.of(creator));
        when(bookingPriceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt())).thenReturn(100_000L);
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

    // ---- fees come from platform settings, not from static config ----

    @Test
    void casualGameTakesItsServiceFeeFromPlatformSettings() {
        stubCreate();
        when(platformSettingsService.current()).thenReturn(PlatformSettings.builder()
                .casualGameServiceFee(700_000L).teamGamePlatformFee(2_500_000L).build());

        service.createGame(CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10)
                .startsAt(startAt(18, 1)).duration(1).build(), CREATOR_ID);

        ArgumentCaptor<Game> captor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(captor.capture());
        assertThat(captor.getValue().getServiceFeePerPerson()).isEqualTo(700_000L);
    }

    @Test
    void teamGameTakesTheTeamFeeFromPlatformSettings() {
        stubCreate();
        when(teamRepository.findById(TEAM_A_ID)).thenReturn(Optional.of(
                Team.builder().id(TEAM_A_ID).leader(creator).build()));
        when(platformSettingsService.current()).thenReturn(PlatformSettings.builder()
                .casualGameServiceFee(700_000L).teamGamePlatformFee(2_500_000L).build());

        service.createGame(CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("team_vs_team").maxPlayers(10).teamAId(TEAM_A_ID)
                .startsAt(startAt(18, 1)).duration(1).build(), CREATOR_ID);

        ArgumentCaptor<Game> captor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(captor.capture());
        assertThat(captor.getValue().getServiceFeePerPerson()).isEqualTo(2_500_000L);
    }

    // ---- booking window (a confirmed game becomes a source=game booking) ----

    @Test
    void createGameRejectsStartNotOnTheHour() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(CREATOR_ID)).thenReturn(Optional.of(creator));

        OffsetDateTime halfPast = LocalDate.now(TASHKENT).plusDays(1).atTime(18, 30)
                .atZone(TASHKENT).toOffsetDateTime();
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10).startsAt(halfPast).duration(1).build();

        assertThatThrownBy(() -> service.createGame(req, CREATOR_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Booking must start on the hour");
        verify(gameRepository, never()).save(any());
    }

    @Test
    void createGameRejectsStartBeforeOpeningTime() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(CREATOR_ID)).thenReturn(Optional.of(creator));

        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10).startsAt(startAt(3, 1)).duration(1).build();

        assertThatThrownBy(() -> service.createGame(req, CREATOR_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must start at or after 08:00");
        verify(gameRepository, never()).save(any());
    }

    @Test
    void createGameRejectsEndingAfterClosingTime() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(CREATOR_ID)).thenReturn(Optional.of(creator));

        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10).startsAt(startAt(22, 1)).duration(2).build();

        assertThatThrownBy(() -> service.createGame(req, CREATOR_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must end by 23:00");
        verify(gameRepository, never()).save(any());
    }

    @Test
    void createGameRejectsSlotWithinClosure() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(CREATOR_ID)).thenReturn(Optional.of(creator));
        when(closureService.isClosedAt(eq(STADIUM_ID), any(), any())).thenReturn(true);

        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10).startsAt(startAt(18, 1)).duration(1).build();

        assertThatThrownBy(() -> service.createGame(req, CREATOR_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Stadium is closed");
        verify(gameRepository, never()).save(any());
    }

    // ---- invite link ----

    @Test
    void inviteLinkIsOmittedWhenBotUsernameIsNotConfigured() {
        stubCreate(); // AppProperties default: app.bot.username is null
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10).startsAt(startAt(18, 1)).duration(1).build();

        GameDetailResponse detail = service.createGame(req, CREATOR_ID);

        assertThat(detail.getInviteLink()).isNull();
    }

    @Test
    void inviteLinkUsesTheConfiguredBotUsername() {
        AppProperties props = new AppProperties();
        props.getBot().setUsername("minipolya_bot");
        service = new GameService(gameRepository, participantRepository, stadiumRepository, userRepository,
                teamRepository, bookingPriceCalculator, bookingRepository,
                gameMapper, participantMapper, props, paymentService, notificationService,
                auditService, new BookingWindowValidator(closureService), platformSettingsService, teamGameService,
                mock(SavedCardService.class));
        stubCreate();
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10).startsAt(startAt(18, 1)).duration(1).build();

        GameDetailResponse detail = service.createGame(req, CREATOR_ID);

        assertThat(detail.getInviteLink()).isEqualTo("t.me/minipolya_bot?start=game_" + GAME_ID);
    }

    // ---- createGame ----

    @Test
    void createCasualGameAddsCreatorAsParticipant() {
        stubCreate();
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10).startsAt(startAt(18, 1)).duration(1).build();

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
                .stadiumId(STADIUM_ID).type("open").maxPlayers(10).startsAt(startAt(18, 1)).duration(1).build();

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
                .stadiumId(STADIUM_ID).type("team_vs_team").maxPlayers(2).startsAt(startAt(18, 1)).duration(1).teamAId(TEAM_A_ID).build();

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

        service.joinGame(GAME_ID, JOINER_ID, null, null, null);

        verify(participantRepository).save(any(GameParticipant.class));
        AuditEntry entry = Audited.one(auditService, AuditAction.GAME_JOINED);
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.PLAYER);
        assertThat(entry.getActorId()).isEqualTo(JOINER_ID);
        assertThat(entry.getChanges()).containsExactly(AuditChange.of("players", 0, 1));
    }

    @Test
    void joinFullGameIsRejected() {
        Game game = Game.builder().id(GAME_ID).type("open").status("open").maxPlayers(1).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, JOINER_ID)).thenReturn(Optional.empty());
        when(participantRepository.findByGameId(GAME_ID)).thenReturn(List.of(
                GameParticipant.builder().id(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> service.joinGame(GAME_ID, JOINER_ID, null, null, null)).isInstanceOf(BadRequestException.class);
        verify(participantRepository, never()).save(any());
    }

    @Test
    void joinGameAlreadyInIsRejected() {
        Game game = Game.builder().id(GAME_ID).type("open").status("open").maxPlayers(10).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, JOINER_ID))
                .thenReturn(Optional.of(GameParticipant.builder().id(UUID.randomUUID()).build()));

        assertThatThrownBy(() -> service.joinGame(GAME_ID, JOINER_ID, null, null, null)).isInstanceOf(BadRequestException.class);
        verify(participantRepository, never()).save(any());
    }

    // ---- joining a team_vs_team game as team B ----

    /** An open team_vs_team game whose team A is already set by the creator. */
    private Game tvtGameWithTeamA() {
        Team teamA = Team.builder().id(TEAM_A_ID).name("Team Alpha")
                .leader(User.builder().id(CREATOR_ID).build()).build();
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("open").maxPlayers(10)
                .creator(creator).stadium(stadium).teamA(teamA).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, LEADER_B_ID)).thenReturn(Optional.empty());
        return game;
    }

    @Test
    void joiningAsTeamBWithAnUnknownTeamIsRejected() {
        Game game = tvtGameWithTeamA();
        when(teamRepository.findById(TEAM_B_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.joinGame(GAME_ID, LEADER_B_ID, "b", TEAM_B_ID, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Team not found");

        assertThat(game.getTeamB()).isNull();
        verify(participantRepository, never()).save(any());
    }

    @Test
    void anInvitedMemberJoinsWithoutASideAndWithoutATeamId() {
        Game game = tvtGameWithTeamA();
        when(participantRepository.findByGameIdAndUserId(GAME_ID, JOINER_ID)).thenReturn(Optional.empty());
        when(userRepository.findById(JOINER_ID))
                .thenReturn(Optional.of(User.builder().id(JOINER_ID).name("Member").build()));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantMapper.toResponse(any())).thenReturn(new GameParticipantResponse());

        service.joinGame(GAME_ID, JOINER_ID, null, null, null);

        assertThat(game.getTeamB()).isNull();
        ArgumentCaptor<GameParticipant> captor = ArgumentCaptor.forClass(GameParticipant.class);
        verify(participantRepository).save(captor.capture());
        assertThat(captor.getValue().getSide()).isNull();
    }

    @Test
    void aTeamIdIsIgnoredWhenJoiningACasualGame() {
        Game game = Game.builder().id(GAME_ID).type("casual").status("open").maxPlayers(10)
                .creator(creator).stadium(stadium).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, JOINER_ID)).thenReturn(Optional.empty());
        when(participantRepository.findByGameId(GAME_ID)).thenReturn(List.of());
        when(userRepository.findById(JOINER_ID))
                .thenReturn(Optional.of(User.builder().id(JOINER_ID).name("Joiner").build()));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantMapper.toResponse(any())).thenReturn(new GameParticipantResponse());

        service.joinGame(GAME_ID, JOINER_ID, null, TEAM_B_ID, null);

        assertThat(game.getTeamB()).isNull();
        verify(teamRepository, never()).findById(TEAM_B_ID);
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
                .creator(creator).stadium(stadium).startsAt(start).endsAt(start.plusHours(1)).totalPrice(100_000L).build();
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

        // Nobody confirmed it by hand: the last payment landing did.
        AuditEntry entry = Audited.one(auditService, AuditAction.GAME_CONFIRMED);
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(entry.getActorId()).isNull();
        assertThat(entry.getChanges()).containsExactly(
                AuditChange.of("status", "open", "confirmed"),
                AuditChange.of("bookingId", null, BOOKING_ID));
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
        verify(paymentService).refundInFull(paid.getPayment(), "Slot taken by another booking");

        AuditEntry entry = Audited.one(auditService, AuditAction.GAME_AUTO_CANCELLED);
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(entry.getReason()).isEqualTo("Slot taken by another booking");
    }

    // ---- cancelUnfilledGames (GameTimeoutJob) ----

    @Test
    void cancelUnfilledGamesCancelsExpiredOpenGames() {
        OffsetDateTime pastStart = inHours(-1);
        Game game = Game.builder().id(GAME_ID).type("casual").status("open").stadium(stadium)
                .startsAt(pastStart).endsAt(pastStart.plusHours(1)).build();
        Payment payment = Payment.builder().id(PAYMENT_ID).build();
        GameParticipant paid = GameParticipant.builder().id(UUID.randomUUID()).game(game).user(creator)
                .status(GameParticipant.Status.PAID).payment(payment).build();
        when(gameRepository.findByStatusAndTypeAndStartsAtBefore(
                eq("open"), eq("team_vs_team"), any(OffsetDateTime.class))).thenReturn(List.of());
        when(gameRepository.findByStatusAndTypeNotAndStartsAtBefore(
                eq("open"), eq("team_vs_team"), any(OffsetDateTime.class))).thenReturn(List.of(game));
        when(participantRepository.findByGameId(GAME_ID)).thenReturn(List.of(paid));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = service.cancelUnfilledGames();

        assertThat(count).isEqualTo(1);
        assertThat(game.getStatus()).isEqualTo("cancelled_not_filled");
        assertThat(paid.getStatus()).isEqualTo(GameParticipant.Status.REFUNDED);
        verify(paymentService).refundInFull(paid.getPayment(), "Game did not fill in time");

        // A SYSTEM action: no user, the status change, and why.
        AuditEntry entry = Audited.one(auditService, AuditAction.GAME_AUTO_CANCELLED);
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.SYSTEM);
        assertThat(entry.getActorId()).isNull();
        assertThat(entry.getEntityId()).isEqualTo(GAME_ID);
        assertThat(entry.getChanges()).containsExactly(AuditChange.of("status", "open", "cancelled_not_filled"));
        assertThat(entry.getReason()).isEqualTo("Game did not fill in time");
    }

    @Test
    void confirmingATeamGameClearsTheOtherWaitingGamesOnThatSlot() {
        OffsetDateTime start = inHours(48);
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("open").stadium(stadium)
                .startsAt(start).endsAt(start.plusHours(1)).maxPlayers(10).totalPrice(100_000L).build();
        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(GAME_ID, LEADER_B_ID)).thenReturn(Optional.empty());
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(userRepository.findById(LEADER_B_ID)).thenReturn(Optional.of(
                User.builder().id(LEADER_B_ID).name("Leader B").build()));
        when(teamRepository.findById(TEAM_B_ID)).thenReturn(Optional.of(
                Team.builder().id(TEAM_B_ID).name("Team B").build()));
        when(gameRepository.findByStadiumIdAndStatus(STADIUM_ID, "open")).thenReturn(List.of());

        service.joinGame(GAME_ID, LEADER_B_ID, "b", TEAM_B_ID, UUID.randomUUID());

        // Taking the slot has to clear other teams still waiting on it, exactly
        // as an ordinary booking does — otherwise their money stays on hold for
        // a pitch that is gone.
        verify(gameRepository).findByStadiumIdAndStatus(STADIUM_ID, "open");
    }

    @Test
    void aWaitingTeamGameOnATakenSlotIsEndedByTheTeamFlow() {
        OffsetDateTime start = inHours(24);
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("open").stadium(stadium)
                .startsAt(start).endsAt(start.plusHours(1)).build();
        when(gameRepository.findByStadiumIdAndStatus(STADIUM_ID, "open")).thenReturn(List.of(game));

        service.cancelGamesOnSlot(STADIUM_ID, start, start.plusHours(2));

        // A team's money hangs off game_teams, not off participants, so the
        // participant refund path must not be used for it.
        verify(teamGameService).endWaitingGame(eq(game), eq("cancelled_slot_taken"), anyString());
        verify(paymentService, never()).refundInFull(any(), anyString());
    }

    @Test
    void aWaitingTeamGameIsCancelledThreeHoursBeforeKickOffNotAtIt() {
        OffsetDateTime start = inHours(2);
        Game game = Game.builder().id(GAME_ID).type("team_vs_team").status("open").stadium(stadium)
                .startsAt(start).endsAt(start.plusHours(1)).build();
        when(gameRepository.findByStatusAndTypeAndStartsAtBefore(
                eq("open"), eq("team_vs_team"), any(OffsetDateTime.class))).thenReturn(List.of(game));
        when(gameRepository.findByStatusAndTypeNotAndStartsAtBefore(
                eq("open"), eq("team_vs_team"), any(OffsetDateTime.class))).thenReturn(List.of());

        assertThat(service.cancelUnfilledGames()).isEqualTo(1);

        verify(teamGameService).endWaitingGame(eq(game), eq("cancelled_not_filled"), anyString());
    }

    @Test
    void theTeamGameCutoffIsThreeHoursAheadOfNow() {
        when(gameRepository.findByStatusAndTypeAndStartsAtBefore(any(), any(), any())).thenReturn(List.of());
        when(gameRepository.findByStatusAndTypeNotAndStartsAtBefore(any(), any(), any())).thenReturn(List.of());

        service.cancelUnfilledGames();

        ArgumentCaptor<OffsetDateTime> teamCutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> otherCutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(gameRepository).findByStatusAndTypeAndStartsAtBefore(
                eq("open"), eq("team_vs_team"), teamCutoff.capture());
        verify(gameRepository).findByStatusAndTypeNotAndStartsAtBefore(
                eq("open"), eq("team_vs_team"), otherCutoff.capture());

        // Casual games still time out at their start; team games three hours earlier.
        assertThat(Duration.between(otherCutoff.getValue(), teamCutoff.getValue()).toHours()).isEqualTo(3);
    }
}
