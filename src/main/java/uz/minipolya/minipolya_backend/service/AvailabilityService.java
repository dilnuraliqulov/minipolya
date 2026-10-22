package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.response.AvailabilityResponse;
import uz.minipolya.minipolya_backend.dto.response.AvailabilityResponse.TimeSlot;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
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
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Computes free/occupied 1-hour slots for a stadium on a Tashkent-local date,
 * folding in bookings, closures, pricing rules, promotions and waiting games.
 * Reads existing tables only — no schema of its own.
 */
@Service
@RequiredArgsConstructor
public class AvailabilityService {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final String STATUS_APPROVED = "approved";

    private final StadiumRepository stadiumRepository;
    private final BookingRepository bookingRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final GameRepository gameRepository;
    private final GameParticipantRepository participantRepository;
    private final PriceCalculator priceCalculator;
    private final PromotionService promotionService;
    private final StadiumClosureService closureService;
    private final AppProperties appProperties;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AvailabilityResponse getAvailability(UUID stadiumId, LocalDate date) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(stadiumId, STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));

        ZonedDateTime nowLocal = ZonedDateTime.now(clock.withZone(TASHKENT));
        LocalDate today = nowLocal.toLocalDate();
        LocalDate queryDate = date != null ? date : today;
        if (queryDate.isBefore(today)) {
            throw new BadRequestException("date cannot be in the past");
        }
        if (queryDate.isAfter(today.plusWeeks(appProperties.getBooking().getMaxWeeksAhead()))) {
            throw new BadRequestException(
                    "date is beyond the " + appProperties.getBooking().getMaxWeeksAhead() + "-week booking window");
        }

        OffsetDateTime dayStart = queryDate.atStartOfDay(TASHKENT).toOffsetDateTime();
        OffsetDateTime dayEnd = queryDate.plusDays(1).atStartOfDay(TASHKENT).toOffsetDateTime();

        List<Booking> bookings = bookingRepository.findActiveForStadiumBetween(stadiumId, dayStart, dayEnd);
        List<PricingRule> rules = pricingRuleRepository.findByStadiumIdAndIsActiveTrue(stadiumId);
        List<Game> waitingGames = gameRepository.findByStadiumIdAndStatus(stadiumId, "open").stream()
                .filter(g -> !g.getStartsAt().isBefore(dayStart) && g.getStartsAt().isBefore(dayEnd))
                .toList();

        // On today's date an hour that has already started can no longer be booked,
        // so it is left out entirely rather than offered as free.
        boolean isToday = queryDate.equals(today);
        LocalTime localNow = nowLocal.toLocalTime();

        List<TimeSlot> slots = new ArrayList<>();
        LocalTime slotStart = stadium.getOpenTime();
        while (slotStart.isBefore(stadium.getCloseTime())) {
            LocalTime slotEnd = slotStart.plusHours(1);
            if (slotEnd.isAfter(stadium.getCloseTime())) {
                break; // no room for a full hour before closing
            }
            if (!(isToday && !slotStart.isAfter(localNow))) {
                slots.add(buildSlot(stadium, queryDate, slotStart, slotEnd, bookings, rules, waitingGames));
            }
            slotStart = slotEnd;
        }

        return AvailabilityResponse.builder()
                .stadiumId(stadiumId)
                .stadiumName(stadium.getName())
                .date(queryDate)
                .slots(slots)
                .build();
    }

    private TimeSlot buildSlot(Stadium stadium, LocalDate date, LocalTime slotStart, LocalTime slotEnd,
                               List<Booking> bookings, List<PricingRule> rules, List<Game> waitingGames) {
        OffsetDateTime slotStartUtc = date.atTime(slotStart).atZone(TASHKENT).toOffsetDateTime();
        OffsetDateTime slotEndUtc = date.atTime(slotEnd).atZone(TASHKENT).toOffsetDateTime();

        boolean booked = bookings.stream()
                .anyMatch(b -> b.getStartsAt().isBefore(slotEndUtc) && slotStartUtc.isBefore(b.getEndsAt()));
        boolean closed = closureService.isClosedAt(stadium.getId(), date, slotStart);
        boolean available = !booked && !closed;

        TimeSlot.TimeSlotBuilder slot = TimeSlot.builder()
                .startTime(slotStart)
                .endTime(slotEnd)
                .available(available);

        if (available) {
            int base = priceCalculator.calculateTotalPrice(rules, date, slotStart, 1, stadium.getPricePerHour());
            Optional<Promotion> promo = promotionService.getBestPromotion(stadium.getId(), slotStartUtc);
            if (promo.isPresent()) {
                int percent = promo.get().getDiscountPercent();
                slot.pricePerHour(BookingPriceCalculator.applyDiscount(base, percent))
                        .originalPrice(base)
                        .discountPercent(percent)
                        .promotionName(promo.get().getName());
            } else {
                slot.pricePerHour(base);
            }
        }

        waitingGames.stream()
                .filter(g -> g.getStartsAt().isBefore(slotEndUtc) && slotStartUtc.isBefore(g.getEndsAt()))
                .findFirst()
                .ifPresent(game -> slot.hasWaitingGame(true)
                        .waitingGameId(game.getId())
                        .waitingGameType(game.getType())
                        .playersJoined(participantRepository.findByGameId(game.getId()).size())
                        .playersNeeded(game.getMaxPlayers()));

        return slot.build();
    }
}
