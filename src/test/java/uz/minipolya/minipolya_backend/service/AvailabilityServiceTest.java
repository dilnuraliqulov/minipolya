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

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AvailabilityServiceTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

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

        service = new AvailabilityService(stadiumRepository, bookingRepository, pricingRuleRepository,
                gameRepository, participantRepository, new PriceCalculator(), promotionService,
                closureService, new AppProperties());

        stadium = Stadium.builder().id(1L).name("Arena").status("approved").isActive(true)
                .pricePerHour(50_000).openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0)).build();
        date = LocalDate.now(TASHKENT).plusDays(1);
    }

    private void stubStadium() {
        when(stadiumRepository.findByIdAndStatus(1L, "approved")).thenReturn(Optional.of(stadium));
    }

    private OffsetDateTime at(LocalTime time) {
        return date.atTime(time).atZone(TASHKENT).toOffsetDateTime();
    }

    private TimeSlot slotAt(AvailabilityResponse response, LocalTime start) {
        return response.getSlots().stream()
                .filter(s -> s.getStartTime().equals(start))
                .findFirst().orElseThrow();
    }

    @Test
    void generatesOneHourSlotsAcrossOpeningHours() {
        stubStadium();

        AvailabilityResponse response = service.getAvailability(1L, date);

        assertThat(response.getSlots()).hasSize(15); // 08:00-23:00
        assertThat(response.getSlots().get(0).getStartTime()).isEqualTo(LocalTime.of(8, 0));
        assertThat(response.getSlots().get(14).getEndTime()).isEqualTo(LocalTime.of(23, 0));
        assertThat(response.getSlots()).allMatch(TimeSlot::isAvailable);
    }

    @Test
    void confirmedBookingMakesSlotUnavailable() {
        stubStadium();
        Booking booking = Booking.builder().id(1L).status("confirmed")
                .startsAt(at(LocalTime.of(10, 0))).endsAt(at(LocalTime.of(11, 0))).build();
        when(bookingRepository.findActiveForStadiumBetween(eq(1L), any(), any())).thenReturn(List.of(booking));

        AvailabilityResponse response = service.getAvailability(1L, date);

        assertThat(slotAt(response, LocalTime.of(10, 0)).isAvailable()).isFalse();
        assertThat(slotAt(response, LocalTime.of(9, 0)).isAvailable()).isTrue();
    }

    @Test
    void pendingBookingAlsoHoldsSlot() {
        stubStadium();
        Booking booking = Booking.builder().id(1L).status("pending")
                .startsAt(at(LocalTime.of(15, 0))).endsAt(at(LocalTime.of(16, 0))).build();
        when(bookingRepository.findActiveForStadiumBetween(eq(1L), any(), any())).thenReturn(List.of(booking));

        AvailabilityResponse response = service.getAvailability(1L, date);

        assertThat(slotAt(response, LocalTime.of(15, 0)).isAvailable()).isFalse();
    }

    @Test
    void closureMakesSlotUnavailable() {
        stubStadium();
        when(closureService.isClosedAt(eq(1L), eq(date), any()))
                .thenAnswer(inv -> inv.getArgument(2).equals(LocalTime.of(12, 0)));

        AvailabilityResponse response = service.getAvailability(1L, date);

        assertThat(slotAt(response, LocalTime.of(12, 0)).isAvailable()).isFalse();
        assertThat(slotAt(response, LocalTime.of(8, 0)).isAvailable()).isTrue();
    }

    @Test
    void pricingRuleSetsSlotPrice() {
        stubStadium();
        int dow = date.getDayOfWeek().getValue() - 1;
        PricingRule rule = PricingRule.builder().dayOfWeek(dow)
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(22, 0)).pricePerHour(80_000).isActive(true).build();
        when(pricingRuleRepository.findByStadiumIdAndIsActiveTrue(1L)).thenReturn(List.of(rule));

        AvailabilityResponse response = service.getAvailability(1L, date);

        assertThat(slotAt(response, LocalTime.of(18, 0)).getPricePerHour()).isEqualTo(80_000);
        assertThat(slotAt(response, LocalTime.of(8, 0)).getPricePerHour()).isEqualTo(50_000);
    }

    @Test
    void noPricingRuleUsesStadiumDefaultPrice() {
        stubStadium();

        AvailabilityResponse response = service.getAvailability(1L, date);

        assertThat(slotAt(response, LocalTime.of(8, 0)).getPricePerHour()).isEqualTo(50_000);
    }

    @Test
    void activePromotionAppliesDiscount() {
        stubStadium();
        when(promotionService.getBestPromotion(eq(1L), any())).thenReturn(Optional.of(
                Promotion.builder().discountPercent(20).name("Happy Hour").build()));

        AvailabilityResponse response = service.getAvailability(1L, date);

        TimeSlot slot = slotAt(response, LocalTime.of(8, 0));
        assertThat(slot.getDiscountPercent()).isEqualTo(20);
        assertThat(slot.getOriginalPrice()).isEqualTo(50_000);
        assertThat(slot.getPricePerHour()).isEqualTo(40_000);
        assertThat(slot.getPromotionName()).isEqualTo("Happy Hour");
    }

    @Test
    void waitingOpenGameIsSurfacedOnSlot() {
        stubStadium();
        Game game = Game.builder().id(9L).type("open").status("open").maxPlayers(10)
                .startsAt(at(LocalTime.of(14, 0))).endsAt(at(LocalTime.of(15, 0))).build();
        when(gameRepository.findByStadiumIdAndStatus(1L, "open")).thenReturn(List.of(game));
        when(participantRepository.findByGameId(9L)).thenReturn(List.of(
                new GameParticipant(), new GameParticipant(), new GameParticipant()));

        AvailabilityResponse response = service.getAvailability(1L, date);

        TimeSlot slot = slotAt(response, LocalTime.of(14, 0));
        assertThat(slot.isHasWaitingGame()).isTrue();
        assertThat(slot.getWaitingGameId()).isEqualTo(9L);
        assertThat(slot.getWaitingGameType()).isEqualTo("open");
        assertThat(slot.getPlayersJoined()).isEqualTo(3);
        assertThat(slot.getPlayersNeeded()).isEqualTo(10);
        assertThat(slot.isAvailable()).isTrue();
    }

    @Test
    void dateInThePastIsRejected() {
        stubStadium();

        assertThatThrownBy(() -> service.getAvailability(1L, LocalDate.now(TASHKENT).minusDays(1)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void dateBeyondBookingWindowIsRejected() {
        stubStadium();

        assertThatThrownBy(() -> service.getAvailability(1L, LocalDate.now(TASHKENT).plusWeeks(5)))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void missingStadiumIsNotFound() {
        when(stadiumRepository.findByIdAndStatus(1L, "approved")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAvailability(1L, date))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
