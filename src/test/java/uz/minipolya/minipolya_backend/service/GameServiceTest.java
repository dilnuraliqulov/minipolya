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
    private GameService service;

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

        service = new GameService(gameRepository, participantRepository, stadiumRepository, userRepository,
                teamRepository, pricingRuleRepository, priceCalculator, bookingRepository,
                gameMapper, participantMapper, new AppProperties(), paymentService);

        creator = User.builder().id(7L).name("Creator").build();
        stadium = Stadium.builder().id(1L).status("approved").isActive(true).pricePerHour(50_000).build();
    }

    private OffsetDateTime inHours(long h) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(h);
    }

    private void stubCreate() {
        when(stadiumRepository.findByIdAndStatus(1L, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(7L)).thenReturn(Optional.of(creator));
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(100_000);
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> {
            Game g = inv.getArgument(0);
            if (g.getId() == null) {
                g.setId(100L);
            }
            return g;
        });
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameMapper.toDetailResponse(any(Game.class))).thenReturn(new GameDetailResponse());
        when(participantRepository.findByGameId(100L)).thenReturn(List.of());
    }

    // ---- createGame ----

    @Test
    void createCasualGameAddsCreatorAsParticipant() {
        stubCreate();
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(1L).type("casual").maxPlayers(10).startsAt(inHours(24)).duration(1).build();

        service.createGame(req, 7L);

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
                .stadiumId(1L).type("open").maxPlayers(10).startsAt(inHours(24)).duration(1).build();

        service.createGame(req, 7L);

        ArgumentCaptor<Game> gameCaptor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(gameCaptor.capture());
        assertThat(gameCaptor.getValue().isPublic()).isTrue();
    }

    @Test
    void createTeamVsTeamPutsCreatorOnSideA() {
        stubCreate();
        Team teamA = Team.builder().id(3L).name("A").leader(creator).build();
        when(teamRepository.findById(3L)).thenReturn(Optional.of(teamA));
        CreateGameRequest req = CreateGameRequest.builder()
                .stadiumId(1L).type("team_vs_team").maxPlayers(2).startsAt(inHours(24)).duration(1).teamAId(3L).build();

        service.createGame(req, 7L);

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
        Game game = Game.builder().id(100L).type("open").status("open").maxPlayers(10).build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(100L, 8L)).thenReturn(Optional.empty());
        when(participantRepository.findByGameId(100L)).thenReturn(List.of());
        when(userRepository.findById(8L)).thenReturn(Optional.of(User.builder().id(8L).name("Joiner").build()));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantMapper.toResponse(any())).thenReturn(new GameParticipantResponse());

        service.joinGame(100L, 8L, null);

        verify(participantRepository).save(any(GameParticipant.class));
    }

    @Test
    void joinFullGameIsRejected() {
        Game game = Game.builder().id(100L).type("open").status("open").maxPlayers(1).build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(100L, 8L)).thenReturn(Optional.empty());
        when(participantRepository.findByGameId(100L)).thenReturn(List.of(
                GameParticipant.builder().id(1L).build()));

        assertThatThrownBy(() -> service.joinGame(100L, 8L, null)).isInstanceOf(BadRequestException.class);
        verify(participantRepository, never()).save(any());
    }

    @Test
    void joinGameAlreadyInIsRejected() {
        Game game = Game.builder().id(100L).type("open").status("open").maxPlayers(10).build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(100L, 8L))
                .thenReturn(Optional.of(GameParticipant.builder().id(1L).build()));

        assertThatThrownBy(() -> service.joinGame(100L, 8L, null)).isInstanceOf(BadRequestException.class);
        verify(participantRepository, never()).save(any());
    }

    // ---- handleGamePaymentConfirmed ----

    @Test
    void paymentConfirmedMarksParticipantPaidWithoutConfirmingIfNotFunded() {
        Game game = Game.builder().id(100L).type("casual").status("open").maxPlayers(4).build();
        GameParticipant participant = GameParticipant.builder().id(1L).game(game).user(creator)
                .status(GameParticipant.Status.JOINED).build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(100L, 7L)).thenReturn(Optional.of(participant));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.countByGameIdAndStatus(100L, GameParticipant.Status.PAID)).thenReturn(1);

        service.handleGamePaymentConfirmed(100L, 7L);

        assertThat(participant.getStatus()).isEqualTo(GameParticipant.Status.PAID);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void allPlayersPaidCreatesBookingAndConfirmsCasualGame() {
        OffsetDateTime start = inHours(24);
        Game game = Game.builder().id(100L).type("casual").status("open").maxPlayers(2)
                .creator(creator).stadium(stadium).startsAt(start).endsAt(start.plusHours(1)).totalPrice(100_000).build();
        GameParticipant participant = GameParticipant.builder().id(1L).game(game).user(creator)
                .status(GameParticipant.Status.JOINED).build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(100L, 7L)).thenReturn(Optional.of(participant));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.countByGameIdAndStatus(100L, GameParticipant.Status.PAID)).thenReturn(2);
        when(participantRepository.findByGameId(100L)).thenReturn(List.of(participant));
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(500L);
            return b;
        });
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        service.handleGamePaymentConfirmed(100L, 7L);

        assertThat(game.getStatus()).isEqualTo("confirmed");
        assertThat(game.getBookingId()).isEqualTo(500L);
        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(bookingCaptor.capture());
        assertThat(bookingCaptor.getValue().getSource()).isEqualTo("game");
        assertThat(bookingCaptor.getValue().getStatus()).isEqualTo("confirmed");
    }

    @Test
    void bothLeadersPaidConfirmsTeamVsTeamGame() {
        OffsetDateTime start = inHours(24);
        User leaderB = User.builder().id(9L).name("Leader B").build();
        Game game = Game.builder().id(100L).type("team_vs_team").status("open").creatorSharePct(50)
                .creator(creator).stadium(stadium).startsAt(start).endsAt(start.plusHours(1)).totalPrice(100_000).build();
        GameParticipant leaderBPart = GameParticipant.builder().id(2L).game(game).user(leaderB)
                .side(GameParticipant.Side.B).status(GameParticipant.Status.JOINED).build();
        GameParticipant leaderAPart = GameParticipant.builder().id(1L).game(game).user(creator)
                .side(GameParticipant.Side.A).status(GameParticipant.Status.PAID).build();
        when(gameRepository.findById(100L)).thenReturn(Optional.of(game));
        when(participantRepository.findByGameIdAndUserId(100L, 9L)).thenReturn(Optional.of(leaderBPart));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.findByGameIdAndSide(100L, GameParticipant.Side.A)).thenReturn(List.of(leaderAPart));
        when(participantRepository.findByGameIdAndSide(100L, GameParticipant.Side.B)).thenReturn(List.of(leaderBPart));
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(500L);
            return b;
        });
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        service.handleGamePaymentConfirmed(100L, 9L);

        assertThat(leaderBPart.getStatus()).isEqualTo(GameParticipant.Status.PAID);
        assertThat(game.getStatus()).isEqualTo("confirmed");
        assertThat(game.getBookingId()).isEqualTo(500L);
    }

    // ---- cancelGamesOnSlot ----

    @Test
    void cancelGamesOnSlotCancelsAndRefundsPaidParticipants() {
        OffsetDateTime start = inHours(24);
        Game game = Game.builder().id(100L).status("open").stadium(stadium)
                .startsAt(start).endsAt(start.plusHours(1)).build();
        Payment payment = Payment.builder().id(9L).build();
        GameParticipant paid = GameParticipant.builder().id(1L).game(game).user(creator)
                .status(GameParticipant.Status.PAID).payment(payment).build();
        when(gameRepository.findByStadiumIdAndStatus(1L, "open")).thenReturn(List.of(game));
        when(participantRepository.findByGameId(100L)).thenReturn(List.of(paid));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        service.cancelGamesOnSlot(1L, start, start.plusHours(2));

        assertThat(game.getStatus()).isEqualTo("cancelled_slot_taken");
        assertThat(paid.getStatus()).isEqualTo(GameParticipant.Status.REFUNDED);
        verify(paymentService).refundFullPayment(9L, "Slot taken by another booking");
    }

    // ---- cancelUnfilledGames (GameTimeoutJob) ----

    @Test
    void cancelUnfilledGamesCancelsExpiredOpenGames() {
        OffsetDateTime pastStart = inHours(-1);
        Game game = Game.builder().id(100L).status("open").stadium(stadium)
                .startsAt(pastStart).endsAt(pastStart.plusHours(1)).build();
        Payment payment = Payment.builder().id(9L).build();
        GameParticipant paid = GameParticipant.builder().id(1L).game(game).user(creator)
                .status(GameParticipant.Status.PAID).payment(payment).build();
        when(gameRepository.findByStatusAndStartsAtBefore(eq("open"), any(OffsetDateTime.class)))
                .thenReturn(List.of(game));
        when(participantRepository.findByGameId(100L)).thenReturn(List.of(paid));
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = service.cancelUnfilledGames();

        assertThat(count).isEqualTo(1);
        assertThat(game.getStatus()).isEqualTo("cancelled_not_filled");
        assertThat(paid.getStatus()).isEqualTo(GameParticipant.Status.REFUNDED);
        verify(paymentService).refundFullPayment(9L, "Game did not fill in time");
    }
}
