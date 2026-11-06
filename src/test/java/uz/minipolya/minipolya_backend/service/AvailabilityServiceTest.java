package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.response.AvailabilityResponse;
import uz.minipolya.minipolya_backend.dto.response.AvailabilityResponse.TimeSlot;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.PricingRule;
import uz.minipolya.minipolya_backend.entity.Promotion;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.time.Clock;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AvailabilityServiceTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final UUID STADIUM_ID = UUID.randomUUID();

    private StadiumRepository stadiumRepository;
    private BookingRepository bookingRepository;
    private PricingRuleRepository pricingRuleRepository;
    private GameRepository gameRepository;
    private GameParticipantRepository participantRepository;
    private PromotionService promotionService;
    private StadiumClosureService closureService;
    private AvailabilityService service;

    private Stadium stadium;
    private LocalDate date;

    @BeforeEach
    void setUp() {
        stadiumRepository = mock(StadiumRepository.class);
        bookingRepository = mock(BookingRepository.class);
        pricingRuleRepository = mock(PricingRuleRepository.class);
        gameRepository = mock(GameRepository.class);
        participantRepository = mock(GameParticipantRepository.class);
        promotionService = mock(PromotionService.class);
        closureService = mock(StadiumClosureService.class);

        service = newServiceWithClock(Clock.systemUTC());

        stadium = Stadium.builder().id(STADIUM_ID).name("Arena").status("approved").isActive(true)
                .pricePerHour(50_000L).openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0)).build();
        date = LocalDate.now(TASHKENT).plusDays(1);
    }

    private AvailabilityService newServiceWithClock(Clock clock) {
        return new AvailabilityService(stadiumRepository, bookingRepository, pricingRuleRepository,
                gameRepository, participantRepository, new PriceCalculator(), promotionService,
                closureService, new AppProperties(), clock, CardPricing.withDefaults());
    }

    /** A clock fixed at the given Tashkent-local date and time. */
    private Clock clockAt(LocalDate day, LocalTime time) {
        return Clock.fixed(day.atTime(time).atZone(TASHKENT).toInstant(), TASHKENT);
    }

    private void stubStadium() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
    }

    private OffsetDateTime at(LocalTime time) {
        return date.atTime(time).atZone(TASHKENT).toOffsetDateTime();
    }

    private TimeSlot slotAt(AvailabilityResponse response, LocalTime start) {
        return response.getSlots().stream()
                .filter(s -> s.getStartTime().equals(start))
                .findFirst().orElseThrow();
    }

    // ---- an hour that has already started is listed as past, never free ----

    @Test
    void todayListsHoursThatHaveAlreadyStartedAsPast() {
        stubStadium();
        LocalDate today = LocalDate.of(2026, 10, 27);
        AvailabilityService atTwentyPast = newServiceWithClock(clockAt(today, LocalTime.of(14, 20)));

        AvailabilityResponse response = atTwentyPast.getAvailability(STADIUM_ID, today);

        // Every opening hour is listed; 08:00-14:00 have started at 14:20.
        assertThat(response.getSlots()).hasSize(15);
        List<TimeSlot> past = response.getSlots().stream()
                .filter(s -> s.getStartTime().isBefore(LocalTime.of(15, 0))).toList();
        assertThat(past).hasSize(7).allSatisfy(s -> {
            assertThat(s.getStatus()).isEqualTo("past");
            assertThat(s.isAvailable()).isFalse();
            assertThat(s.getPricePerHour()).isNull();
        });
        List<TimeSlot> later = response.getSlots().stream()
                .filter(s -> !s.getStartTime().isBefore(LocalTime.of(15, 0))).toList();
        assertThat(later).hasSize(8).allSatisfy(s -> {
            assertThat(s.getStatus()).isEqualTo("free");
            assertThat(s.isAvailable()).isTrue();
        });
    }

    @Test
    void anHourStartingExactlyNowIsAlreadyPast() {
        stubStadium();
        LocalDate today = LocalDate.of(2026, 10, 27);
        AvailabilityService atFourteenSharp = newServiceWithClock(clockAt(today, LocalTime.of(14, 0)));

        AvailabilityResponse response = atFourteenSharp.getAvailability(STADIUM_ID, today);

        assertThat(slotAt(response, LocalTime.of(14, 0)).getStatus()).isEqualTo("past");
        assertThat(slotAt(response, LocalTime.of(15, 0)).getStatus()).isEqualTo("free");
    }

    @Test
    void aPastHourStaysPastEvenWhenItWasBooked() {
        stubStadium();
        LocalDate today = LocalDate.of(2026, 10, 27);
        OffsetDateTime tenOClock = today.atTime(10, 0).atZone(TASHKENT).toOffsetDateTime();
        when(bookingRepository.findActiveForStadiumBetween(eq(STADIUM_ID), any(), any()))
                .thenReturn(List.of(Booking.builder().startsAt(tenOClock).endsAt(tenOClock.plusHours(1)).build()));
        AvailabilityService atNoon = newServiceWithClock(clockAt(today, LocalTime.of(12, 0)));

        AvailabilityResponse response = atNoon.getAvailability(STADIUM_ID, today);

        assertThat(slotAt(response, LocalTime.of(10, 0)).getStatus()).isEqualTo("past");
    }

    @Test
    void beforeOpeningTimeTodayStillOffersEveryHour() {
        stubStadium();
        LocalDate today = LocalDate.of(2026, 10, 27);
        AvailabilityService atDawn = newServiceWithClock(clockAt(today, LocalTime.of(6, 30)));

        AvailabilityResponse response = atDawn.getAvailability(STADIUM_ID, today);

        assertThat(response.getSlots()).hasSize(15);
        assertThat(response.getSlots().get(0).getStartTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void afterClosingTimeTodayEveryHourIsPast() {
        stubStadium();
        LocalDate today = LocalDate.of(2026, 10, 27);
        AvailabilityService atNight = newServiceWithClock(clockAt(today, LocalTime.of(23, 30)));

        AvailabilityResponse response = atNight.getAvailability(STADIUM_ID, today);

        assertThat(response.getSlots()).hasSize(15).allMatch(s -> "past".equals(s.getStatus()));
    }

    @Test
    void aFutureDateIsUnaffectedByTheTimeOfDay() {
        stubStadium();
        LocalDate today = LocalDate.of(2026, 10, 27);
        AvailabilityService atTwentyPast = newServiceWithClock(clockAt(today, LocalTime.of(14, 20)));

        AvailabilityResponse response = atTwentyPast.getAvailability(STADIUM_ID, today.plusDays(1));

        assertThat(response.getSlots()).hasSize(15);
        assertThat(response.getSlots().get(0).getStartTime()).isEqualTo(LocalTime.of(8, 0));
    }

    @Test
    void generatesOneHourSlotsAcrossOpeningHours() {
        stubStadium();

        AvailabilityResponse response = service.getAvailability(STADIUM_ID, date);

        assertThat(response.getSlots()).hasSize(15); // 08:00-23:00
        assertThat(response.getSlots().get(0).getStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(response.getSlots().get(14).getEndTime()).isEqualTo(LocalTime.of(23, 0));
        assertThat(response.getSlots()).allMatch(TimeSlot::isAvailable);
    }

    @Test
    void confirmedBookingMakesSlotUnavailable() {
        stubStadium();
        Booking booking = Booking.builder().id(UUID.randomUUID()).status("confirmed")
                .startsAt(at(LocalTime.of(10, 0))).endsAt(at(LocalTime.of(11, 0))).build();
        when(bookingRepository.findActiveForStadiumBetween(eq(STADIUM_ID), any(), any())).thenReturn(List.of(booking));

        AvailabilityResponse response = service.getAvailability(STADIUM_ID, date);

        assertThat(slotAt(response, LocalTime.of(10, 0)).isAvailable()).isFalse();
        assertThat(slotAt(response, LocalTime.of(10, 0)).getStatus()).isEqualTo("booked");
        assertThat(slotAt(response, LocalTime.of(9, 0)).isAvailable()).isTrue();
        assertThat(slotAt(response, LocalTime.of(9, 0)).getStatus()).isEqualTo("free");
    }

    @Test
    void pendingBookingAlsoHoldsSlot() {
        stubStadium();
        Booking booking = Booking.builder().id(UUID.randomUUID()).status("pending")
                .startsAt(at(LocalTime.of(15, 0))).endsAt(at(LocalTime.of(16, 0))).build();
        when(bookingRepository.findActiveForStadiumBetween(eq(STADIUM_ID), any(), any())).thenReturn(List.of(booking));

        AvailabilityResponse response = service.getAvailability(STADIUM_ID, date);

        assertThat(slotAt(response, LocalTime.of(15, 0)).isAvailable()).isFalse();
        assertThat(slotAt(response, LocalTime.of(15, 0)).getStatus()).isEqualTo("booked");
    }

    @Test
    void closureMakesSlotUnavailable() {
        stubStadium();
        when(closureService.isClosedAt(eq(STADIUM_ID), eq(date), any()))
                .thenAnswer(inv -> inv.getArgument(2).equals(LocalTime.of(12, 0)));

        AvailabilityResponse response = service.getAvailability(STADIUM_ID, date);

        assertThat(slotAt(response, LocalTime.of(12, 0)).isAvailable()).isFalse();
        assertThat(slotAt(response, LocalTime.of(12, 0)).getStatus()).isEqualTo("closed");
        assertThat(slotAt(response, LocalTime.of(8, 0)).isAvailable()).isTrue();
    }

    @Test
    void pricingRuleSetsSlotPrice() {
        stubStadium();
        int dow = date.getDayOfWeek().getValue() - 1;
        PricingRule rule = PricingRule.builder().dayOfWeek(dow)
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(22, 0)).pricePerHour(80_000L).isActive(true).build();
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(STADIUM_ID)).thenReturn(List.of(rule));

        AvailabilityResponse response = service.getAvailability(STADIUM_ID, date);

        // Slots show the one price the player pays.
        assertThat(slotAt(response, LocalTime.of(18, 0)).getPricePerHour()).isEqualTo(TestPrices.card(80_000));
        assertThat(slotAt(response, LocalTime.of(8, 0)).getPricePerHour()).isEqualTo(TestPrices.card(50_000));
    }

    @Test
    void noPricingRuleUsesStadiumDefaultPrice() {
        stubStadium();

        AvailabilityResponse response = service.getAvailability(STADIUM_ID, date);

        assertThat(slotAt(response, LocalTime.of(8, 0)).getPricePerHour()).isEqualTo(TestPrices.card(50_000));
    }

    @Test
    void activePromotionAppliesDiscount() {
        stubStadium();
        when(promotionService.getBestPromotion(eq(STADIUM_ID), any())).thenReturn(Optional.of(
                Promotion.builder().discountPercent(20).name("Happy Hour").build()));

        AvailabilityResponse response = service.getAvailability(STADIUM_ID, date);

        TimeSlot slot = slotAt(response, LocalTime.of(8, 0));
        assertThat(slot.getDiscountPercent()).isEqualTo(20);
        // Both grossed up on their own: the discount applies to the owner's net price.
        assertThat(slot.getOriginalPrice()).isEqualTo(TestPrices.card(50_000));
        assertThat(slot.getPricePerHour()).isEqualTo(TestPrices.card(40_000));
        assertThat(slot.getPromotionName()).isEqualTo("Happy Hour");
    }

    @Test
    void aTeamLookingForAnOpponentIsSurfacedOnItsSlot() {
        stubStadium();
        UUID gameId = UUID.randomUUID();
        Game game = Game.builder().id(gameId).type("team_vs_team").status("open").maxPlayers(10)
                .startsAt(at(LocalTime.of(14, 0))).endsAt(at(LocalTime.of(15, 0))).build();
        when(gameRepository.findByStadiumIdAndStatus(STADIUM_ID, "open")).thenReturn(List.of(game));
        when(participantRepository.findByGameId(gameId)).thenReturn(List.of(
                new GameParticipant(), new GameParticipant(), new GameParticipant()));

        AvailabilityResponse response = service.getAvailability(STADIUM_ID, date);

        TimeSlot slot = slotAt(response, LocalTime.of(14, 0));
        assertThat(slot.isHasWaitingGame()).isTrue();
        assertThat(slot.getWaitingGameId()).isEqualTo(gameId);
        assertThat(slot.getWaitingGameType()).isEqualTo("team_vs_team");
        assertThat(slot.getPlayersJoined()).isEqualTo(3);
        assertThat(slot.getPlayersNeeded()).isEqualTo(10);
        // The slot is genuinely free: a waiting game holds no booking.
        assertThat(slot.isAvailable()).isTrue();
    }

    @Test
    void aWaitingCasualGameIsNotMarkedOnTheSlot() {
        stubStadium();
        UUID gameId = UUID.randomUUID();
        Game game = Game.builder().id(gameId).type("casual").status("open").maxPlayers(10)
                .startsAt(at(LocalTime.of(14, 0))).endsAt(at(LocalTime.of(15, 0))).build();
        when(gameRepository.findByStadiumIdAndStatus(STADIUM_ID, "open")).thenReturn(List.of(game));

        AvailabilityResponse response = service.getAvailability(STADIUM_ID, date);

        // "A team is looking for an opponent" is a team-vs-team thing only;
        // a casual game is private to whoever was invited.
        TimeSlot slot = slotAt(response, LocalTime.of(14, 0));
        assertThat(slot.isHasWaitingGame()).isFalse();
        assertThat(slot.getWaitingGameId()).isNull();
    }

    @Test
    void dateInThePastIsRejected() {
        stubStadium();

        assertThatThrownBy(() -> service.getAvailability(STADIUM_ID, LocalDate.now(TASHKENT).minusDays(1)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void dateBeyondBookingWindowIsRejected() {
        stubStadium();

        assertThatThrownBy(() -> service.getAvailability(STADIUM_ID, LocalDate.now(TASHKENT).plusWeeks(5)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void missingStadiumIsNotFound() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAvailability(STADIUM_ID, date))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- E3: each free slot quotes the deposit and remainder the booking would charge ----

    @Test
    void aFreeSlotQuotesTheDepositAndRemainderWhenTheStadiumHasAMinimum() {
        stadium.setPricePerHour(15_000_000L);       // 150 000 so'm -> 155 000 for the player
        stadium.setMinBookingAmount(5_000_000L);    // 50 000 so'm -> 55 000 deposit
        stubStadium();

        TimeSlot slot = slotAt(service.getAvailability(STADIUM_ID, date), LocalTime.of(18, 0));

        assertThat(slot.getPricePerHour()).isEqualTo(15_500_000L);
        assertThat(slot.getDepositAmount()).isEqualTo(5_500_000L);
        assertThat(slot.getRemainderAmount()).isEqualTo(10_000_000L);
        assertThat(slot.getDepositAmount()).isEqualTo(
                CardPricing.withDefaults().deposit(5_000_000L, slot.getPricePerHour()));
    }

    @Test
    void noQuoteWithoutAMinimumOrWhenTheDepositCoversTheSlot() {
        stadium.setPricePerHour(15_000_000L);
        stubStadium();
        TimeSlot fullOnly = slotAt(service.getAvailability(STADIUM_ID, date), LocalTime.of(18, 0));
        assertThat(fullOnly.getDepositAmount()).isNull();
        assertThat(fullOnly.getRemainderAmount()).isNull();

        // A minimum equal to the price: the deposit is the whole price, nothing to choose.
        stadium.setMinBookingAmount(15_000_000L);
        TimeSlot covered = slotAt(service.getAvailability(STADIUM_ID, date), LocalTime.of(18, 0));
        assertThat(covered.getDepositAmount()).isNull();
        assertThat(covered.getRemainderAmount()).isNull();
    }

    @Test
    void aBookedSlotHasNoQuote() {
        stadium.setPricePerHour(15_000_000L);
        stadium.setMinBookingAmount(5_000_000L);
        stubStadium();
        Booking taken = Booking.builder().startsAt(at(LocalTime.of(18, 0))).endsAt(at(LocalTime.of(19, 0))).build();
        when(bookingRepository.findActiveForStadiumBetween(eq(STADIUM_ID), any(), any())).thenReturn(List.of(taken));

        TimeSlot slot = slotAt(service.getAvailability(STADIUM_ID, date), LocalTime.of(18, 0));

        assertThat(slot.getDepositAmount()).isNull();
        assertThat(slot.getRemainderAmount()).isNull();
    }
}
