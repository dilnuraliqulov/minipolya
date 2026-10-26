package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateGameRequest;
import uz.minipolya.minipolya_backend.dto.request.PaymentOption;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.GameDetailResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.PricingRule;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Promotion;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
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
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A game must cost exactly what booking the same slot costs. Both go through
 * {@link BookingPriceCalculator}, which applies the per-hour pricing rule and
 * then the best active promotion, so this test drives the two real services
 * over one real calculator and compares the totals.
 */
class GameBookingPriceParityTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();

    private static final long BASE_PER_HOUR = 130_000L;
    private static final long RULE_PER_HOUR = 155_000L;
    private static final int DISCOUNT_PERCENT = 10;
    private static final UUID CARD_ID = UUID.randomUUID();
    private static final int DURATION_HOURS = 2;

    private Stadium stadium;
    private User player;
    private BookingPriceCalculator priceCalculator;
    private BookingService bookingService;
    private GameService gameService;
    private BookingRepository bookingRepository;
    private GameRepository gameRepository;

    /** 18:00 Tashkent three days out: inside 08:00-23:00 and covered by the rule below. */
    private OffsetDateTime slotStart() {
        return LocalDate.now(TASHKENT).plusDays(3).atTime(18, 0).atZone(TASHKENT).toOffsetDateTime();
    }

    @BeforeEach
    void setUp() {
        User owner = User.builder().id(OWNER_ID).name("Owner").build();
        player = User.builder().id(PLAYER_ID).name("Player").build();
        stadium = Stadium.builder().id(STADIUM_ID).name("Arena").status("approved").isActive(true)
                .pricePerHour(BASE_PER_HOUR).openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0))
                .owner(owner).build();

        // An evening rule covering the slot, plus an active promotion over the whole window.
        PricingRule eveningRule = PricingRule.builder()
                .dayOfWeek(slotStart().atZoneSameInstant(TASHKENT).getDayOfWeek().getValue() - 1)
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(23, 0))
                .pricePerHour(RULE_PER_HOUR).isActive(true).build();
        PricingRuleRepository pricingRuleRepository = mock(PricingRuleRepository.class);
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of(eveningRule));

        PromotionService promotionService = mock(PromotionService.class);
        Promotion promotion = Promotion.builder().name("Autumn 10%").discountPercent(DISCOUNT_PERCENT)
                .isActive(true).build();
        when(promotionService.getBestPromotion(any(UUID.class), any(OffsetDateTime.class)))
                .thenReturn(Optional.of(promotion));

        // The one shared calculator both services use.
        priceCalculator = new BookingPriceCalculator(new PriceCalculator(), pricingRuleRepository, promotionService);

        StadiumClosureService closureService = mock(StadiumClosureService.class);
        BookingWindowValidator windowValidator = new BookingWindowValidator(closureService);
        StadiumRepository stadiumRepository = mock(StadiumRepository.class);
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        UserRepository userRepository = mock(UserRepository.class);
        when(userRepository.findById(PLAYER_ID)).thenReturn(Optional.of(player));


        SavedCardService savedCardService = mock(SavedCardService.class);
        when(savedCardService.requireOwnedCard(any(), any())).thenReturn(
                SavedCard.builder().id(CARD_ID).providerToken("tok-1").isActive(true).build());
        bookingRepository = mock(BookingRepository.class);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        BookingMapper bookingMapper = mock(BookingMapper.class);
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        PaymentService bookingPayments = mock(PaymentService.class);
        bookingService = new BookingService(bookingRepository, stadiumRepository, userRepository,
                priceCalculator, new CancellationPolicy(),
                new BookingResponseFactory(bookingMapper, new CancellationPolicy(), bookingPayments),
                new AppProperties(), bookingPayments, mock(GameService.class),
                mock(NotificationService.class), mock(AuditService.class), windowValidator, savedCardService);

        PlatformSettingsService platformSettings = mock(PlatformSettingsService.class);
        when(platformSettings.current()).thenReturn(PlatformSettings.builder().build());

        gameRepository = mock(GameRepository.class);
        when(gameRepository.save(any(Game.class))).thenAnswer(inv -> inv.getArgument(0));
        GameParticipantRepository participantRepository = mock(GameParticipantRepository.class);
        when(participantRepository.save(any(GameParticipant.class))).thenAnswer(inv -> inv.getArgument(0));
        when(participantRepository.findByGameId(any())).thenReturn(List.of());
        GameMapper gameMapper = mock(GameMapper.class);
        when(gameMapper.toDetailResponse(any(Game.class))).thenReturn(new GameDetailResponse());

        gameService = new GameService(gameRepository, participantRepository, stadiumRepository, userRepository,
                mock(TeamRepository.class), priceCalculator, bookingRepository,
                gameMapper, mock(GameParticipantMapper.class), new AppProperties(),
                mock(PaymentService.class), mock(NotificationService.class), mock(AuditService.class),
                windowValidator, platformSettings, mock(TeamGameService.class));
    }

    @Test
    void aGameCostsExactlyWhatBookingTheSameSlotCosts() {
        OffsetDateTime start = slotStart();

        bookingService.createBooking(PLAYER_ID, CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(start).durationHours(DURATION_HOURS).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build());
        ArgumentCaptor<Booking> bookingCaptor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(bookingCaptor.capture());
        long bookingPrice = bookingCaptor.getValue().getPrice();

        gameService.createGame(CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10)
                .startsAt(start).duration(DURATION_HOURS).build(), PLAYER_ID);
        ArgumentCaptor<Game> gameCaptor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(gameCaptor.capture());
        long gamePrice = gameCaptor.getValue().getTotalPrice();

        assertThat(gamePrice).isEqualTo(bookingPrice);
    }

    @Test
    void thatSharedTotalHasThePromotionApplied() {
        OffsetDateTime start = slotStart();
        long undiscounted = RULE_PER_HOUR * DURATION_HOURS;
        long expected = (RULE_PER_HOUR - RULE_PER_HOUR * DISCOUNT_PERCENT / 100) * DURATION_HOURS;

        gameService.createGame(CreateGameRequest.builder()
                .stadiumId(STADIUM_ID).type("casual").maxPlayers(10)
                .startsAt(start).duration(DURATION_HOURS).build(), PLAYER_ID);

        ArgumentCaptor<Game> gameCaptor = ArgumentCaptor.forClass(Game.class);
        verify(gameRepository).save(gameCaptor.capture());
        long gamePrice = gameCaptor.getValue().getTotalPrice();

        // 2 x 139 500, not 2 x 155 000: the game is no longer charged the pre-promotion rate.
        assertThat(gamePrice).isEqualTo(expected);
        assertThat(gamePrice).isLessThan(undiscounted);
        assertThat(priceCalculator.totalPrice(stadium, start, DURATION_HOURS)).isEqualTo(gamePrice);
    }
}
