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
import uz.minipolya.minipolya_backend.entity.StadiumClosure;
import uz.minipolya.minipolya_backend.entity.StadiumHours;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.repository.BookedSpan;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.repository.StadiumHoursRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;
import uz.minipolya.minipolya_backend.util.WeeklyHours;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final StadiumHoursRepository stadiumHoursRepository;
    private final BookingRepository bookingRepository;
    private final PricingRuleRepository pricingRuleRepository;
    private final GameRepository gameRepository;
    private final GameParticipantRepository participantRepository;
    private final PriceCalculator priceCalculator;
    private final PromotionService promotionService;
    private final StadiumClosureService closureService;
    private final AppProperties appProperties;
    private final Clock clock;
    private final CardPricing cardPricing;

    @Transactional(readOnly = true)
    public AvailabilityResponse getAvailability(UUID stadiumId, LocalDate date) {
        Stadium stadium = stadiumRepository.findByIdAndStatus(stadiumId, STATUS_APPROVED)
                .filter(Stadium::isActive)
                .orElseThrow(() -> new ResourceNotFoundException("Stadium not found"));

        ZonedDateTime nowLocal = nowInTashkent();
        LocalDate today = nowLocal.toLocalDate();
        LocalDate queryDate = date != null ? date : today;
        if (queryDate.isBefore(earliestWorkingDay(stadium, today, nowLocal))) {
            throw new BadRequestException("date cannot be in the past");
        }
        if (queryDate.isAfter(today.plusWeeks(appProperties.getBooking().getMaxWeeksAhead()))) {
            throw new BadRequestException(
                    "date is beyond the " + appProperties.getBooking().getMaxWeeksAhead() + "-week booking window");
        }

        // The working day may run into the next calendar date, so both are loaded.
        OffsetDateTime dayStart = queryDate.atStartOfDay(TASHKENT).toOffsetDateTime();
        OffsetDateTime spanEnd = queryDate.plusDays(2).atStartOfDay(TASHKENT).toOffsetDateTime();

        List<Booking> bookings = bookingRepository.findActiveForStadiumBetween(stadiumId, dayStart, spanEnd);
        List<PricingRule> rules = pricingRuleRepository.findByStadiumIdAndIsActiveTrue(stadiumId);
        // Only a team game waiting for an opponent is advertised on a free slot:
        // "a team is looking for an opponent" is an invitation anyone can take
        // up, whereas a casual game is private to whoever was invited to it.
        List<Game> waitingGames = gameRepository.findByStadiumIdAndStatus(stadiumId, "open").stream()
                .filter(g -> "team_vs_team".equals(g.getType()))
                .filter(g -> !g.getStartsAt().isBefore(dayStart) && g.getStartsAt().isBefore(spanEnd))
                .toList();

        List<BookedSpan> booked = bookings.stream()
                .map(b -> new BookedSpan(stadiumId, b.getStartsAt(), b.getEndsAt()))
                .toList();

        // An hour already started is listed as past, so the day still shows every opening
        // hour. The closure is asked at the working day, so a full-day one covers the night too.
        List<TimeSlot> slots = new ArrayList<>();
        for (SlotRules.Hour hour : SlotRules.hoursOf(stadium.weeklyHours().dayOf(queryDate))) {
            String status = SlotRules.status(queryDate, hour, nowLocal, booked,
                    time -> closureService.isClosedAt(stadiumId, queryDate, time));
            slots.add(TimeSlot.PAST.equals(status)
                    ? pastSlot(queryDate, hour)
                    : buildSlot(stadium, queryDate, hour, status, rules, waitingGames));
        }

        return AvailabilityResponse.builder()
                .stadiumId(stadiumId)
                .stadiumName(stadium.getName())
                .date(queryDate)
                .slots(slots)
                .build();
    }

    /**
     * How many hours of {@code date} can still be booked at each stadium, by the same
     * {@link SlotRules} as {@link #getAvailability}, in two queries however long the list. A
     * day outside the booking window has nothing to book; unlike the availability screen this
     * is not an error, so a list left open past midnight still loads.
     *
     * @param requested the Tashkent date; null means today
     */
    @Transactional(readOnly = true)
    public Map<UUID, Integer> freeSlotCounts(List<Stadium> stadiums, LocalDate requested) {
        if (stadiums.isEmpty()) {
            return Map.of();
        }
        ZonedDateTime nowLocal = nowInTashkent();
        LocalDate today = nowLocal.toLocalDate();
        LocalDate date = requested != null ? requested : today;
        if (date.isBefore(today) || date.isAfter(today.plusWeeks(appProperties.getBooking().getMaxWeeksAhead()))) {
            return stadiums.stream().collect(Collectors.toMap(Stadium::getId, s -> 0));
        }
        List<UUID> ids = stadiums.stream().map(Stadium::getId).toList();
        // Two calendar days: a working day closing after midnight reaches into the next.
        Map<UUID, List<BookedSpan>> booked = bookingRepository.findActiveSpansBetween(ids,
                        date.atStartOfDay(TASHKENT).toOffsetDateTime(),
                        date.plusDays(2).atStartOfDay(TASHKENT).toOffsetDateTime()).stream()
                .collect(Collectors.groupingBy(BookedSpan::stadiumId));
        Map<UUID, List<StadiumClosure>> closures = closureService.closuresOn(ids, date).stream()
                .collect(Collectors.groupingBy(c -> c.getStadium().getId()));
        // One query for the whole page's schedules; reading stadium.weeklyHours() here would
        // lazy-load seven rows per stadium.
        Map<UUID, List<StadiumHours>> hours = stadiumHoursRepository.findByStadiumIdIn(ids).stream()
                .collect(Collectors.groupingBy(row -> row.getStadium().getId()));

        return stadiums.stream().collect(Collectors.toMap(Stadium::getId, stadium -> {
            List<BookedSpan> spans = booked.getOrDefault(stadium.getId(), List.of());
            List<StadiumClosure> closed = closures.getOrDefault(stadium.getId(), List.of());
            WeeklyHours weekly = StadiumHours.toWeekly(hours.get(stadium.getId()),
                    stadium.getOpenTime(), stadium.getCloseTime());
            return (int) SlotRules.hoursOf(weekly.dayOf(date)).stream()
                    .filter(hour -> TimeSlot.FREE.equals(SlotRules.status(date, hour, nowLocal, spans,
                            time -> closed.stream().anyMatch(c -> StadiumClosureService.covers(c, date, time)))))
                    .count();
        }));
    }

    private ZonedDateTime nowInTashkent() {
        return ZonedDateTime.now(clock.withZone(TASHKENT));
    }

    /**
     * The earliest working day that can still be asked about: today, or yesterday while its
     * working day is still running. A stadium open Saturday 08:00-02:00 is still inside
     * Saturday's working day at 00:30 on Sunday, and its 01:00 slot is still bookable, so
     * Saturday must not be refused as a past date.
     */
    private LocalDate earliestWorkingDay(Stadium stadium, LocalDate today, ZonedDateTime nowLocal) {
        LocalDate yesterday = today.minusDays(1);
        boolean stillRunning = SlotRules.hoursOf(stadium.weeklyHours().dayOf(yesterday)).stream()
                .anyMatch(hour -> SlotRules.startOf(yesterday, hour).isAfter(nowLocal.toOffsetDateTime()));
        return stillRunning ? yesterday : today;
    }

    /** An hour that has already started: no price, no deposit, nothing to join. */
    private TimeSlot pastSlot(LocalDate workingDate, SlotRules.Hour hour) {
        return TimeSlot.builder()
                .startTime(hour.start())
                .endTime(hour.end())
                .startsAt(SlotRules.startOf(workingDate, hour))
                .afterMidnight(hour.afterMidnight() ? Boolean.TRUE : null)
                .available(false)
                .status(TimeSlot.PAST)
                .build();
    }

    /**
     * @param workingDate the day whose opening this hour belongs to - what the pricing
     *                    rules and promotions match on, even for an hour after midnight
     */
    private TimeSlot buildSlot(Stadium stadium, LocalDate workingDate, SlotRules.Hour hour,
                               String status, List<PricingRule> rules, List<Game> waitingGames) {
        LocalTime slotStart = hour.start();
        OffsetDateTime slotStartUtc = SlotRules.startOf(workingDate, hour);
        OffsetDateTime slotEndUtc = slotStartUtc.plusHours(1);

        boolean available = TimeSlot.FREE.equals(status);

        TimeSlot.TimeSlotBuilder slot = TimeSlot.builder()
                .startTime(slotStart)
                .endTime(hour.end())
                .startsAt(slotStartUtc)
                .afterMidnight(hour.afterMidnight() ? Boolean.TRUE : null)
                .available(available)
                .status(status);

        if (available) {
            // The WORKING day's weekday selects the rules and the promotion, so Saturday's
            // 00:00 slot is priced by Saturday's rules for 00:00-01:00.
            long base = priceCalculator.hourPrice(rules, workingDate, slotStart, stadium.getPricePerHour());
            Optional<Promotion> promo = promotionService.getBestPromotion(stadium.getId(), slotStartUtc,
                    WeeklyHours.dayOfWeekOf(workingDate));
            // The same owner price per hour a booking is priced from, shown as the player
            // price, so the slots a player picks add up to exactly what the booking charges.
            long net = promo.map(p -> BookingPriceCalculator.applyDiscount(base, p.getDiscountPercent())).orElse(base);
            long playerPrice = cardPricing.playerAmount(net);
            slot.pricePerHour(playerPrice);
            // E3: the deposit and remainder a MINIMUM booking of this hour would charge, by the
            // same CardPricing.deposit that POST /api/bookings uses, from the stadium's effective
            // minimum (T3: its own or the platform default). Absent only when the deposit would
            // cover the whole hour: then it is full payment only.
            long deposit = cardPricing.deposit(cardPricing.effectiveMinimum(stadium.getMinBookingAmount()), playerPrice);
            if (deposit < playerPrice) {
                slot.depositAmount(deposit).remainderAmount(playerPrice - deposit);
            }
            if (promo.isPresent()) {
                slot.originalPrice(cardPricing.playerAmount(base))
                        .discountPercent(promo.get().getDiscountPercent())
                        .promotionName(promo.get().getName());
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
