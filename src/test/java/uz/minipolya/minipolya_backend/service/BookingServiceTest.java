package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateManualBookingRequest;
import uz.minipolya.minipolya_backend.dto.request.PaymentOption;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.BookingExpiredException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.exception.SlotAlreadyBookedException;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

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

class BookingServiceTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID FOREIGN_USER_ID = UUID.randomUUID();
    private static final UUID NON_OWNER_ID = UUID.randomUUID();
    private static final UUID MANUAL_BOOKING_ID = UUID.randomUUID();
    private static final UUID CARD_ID = UUID.randomUUID();

    private BookingRepository bookingRepository;
    private StadiumRepository stadiumRepository;
    private UserRepository userRepository;
    private BookingPriceCalculator bookingPriceCalculator;
    private BookingMapper bookingMapper;
    private PaymentService paymentService;
    private GameService gameService;
    private NotificationService notificationService;
    private AuditService auditService;
    private StadiumClosureService closureService;
    private SavedCardService savedCardService;
    private BookingService service;

    private User player;
    private User owner;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        stadiumRepository = mock(StadiumRepository.class);
        userRepository = mock(UserRepository.class);
        bookingPriceCalculator = mock(BookingPriceCalculator.class);
        bookingMapper = mock(BookingMapper.class);
        paymentService = mock(PaymentService.class);
        gameService = mock(GameService.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        closureService = mock(StadiumClosureService.class);

        savedCardService = mock(SavedCardService.class);
        when(savedCardService.requireOwnedCard(eq(USER_ID), eq(CARD_ID))).thenReturn(
                SavedCard.builder().id(CARD_ID).providerToken("tok-1").isActive(true).build());

        service = new BookingService(bookingRepository, stadiumRepository, userRepository,
                bookingPriceCalculator, new CancellationPolicy(),
                new BookingResponseFactory(bookingMapper, new CancellationPolicy(), paymentService),
                new AppProperties(),
                paymentService, gameService, notificationService, auditService,
                new BookingWindowValidator(closureService), savedCardService);

        player = User.builder().id(USER_ID).name("Player").build();
        owner = User.builder().id(OWNER_ID).name("Owner").build();
        stadium = Stadium.builder().id(STADIUM_ID).status("approved").isActive(true).pricePerHour(50_000L)
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0))
                .cancellationHours(24).owner(owner).build();
    }

    /** A future start aligned to the hour in Tashkent, within the 08:00–23:00 window. */
    private OffsetDateTime startAt(int tashkentHour, long daysAhead) {
        return LocalDate.now(TASHKENT).plusDays(daysAhead).atTime(tashkentHour, 0)
                .atZone(TASHKENT).toOffsetDateTime();
    }

    /** A relative UTC instant — for cancellation-window tests that don't go through booking validation. */
    private OffsetDateTime inHours(long h) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(h);
    }

    // ---- createBooking ----

    @Test
    void createBookingHoldsSlotAsPending() {
        OffsetDateTime start = startAt(18, 2);
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(player));
        when(bookingPriceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(100_000L);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(start).durationHours(2).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        service.createBooking(USER_ID, req);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        Booking saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("pending");
        assertThat(saved.getSource()).isEqualTo("app");
        assertThat(saved.getPrice()).isEqualTo(100_000);
        assertThat(saved.getDurationHours()).isEqualTo(2);
        assertThat(saved.getEndsAt()).isEqualTo(start.plusHours(2));
        assertThat(saved.getExpiresAt()).isNotNull();
        assertThat(saved.getUser()).isSameAs(player);
        assertThat(saved.getStadium()).isSameAs(stadium);
    }

    @Test
    void createBookingAllowsAnyNumberOfConsecutiveHours() {
        OffsetDateTime start = startAt(18, 2); // 18:00-21:00, within 08:00-23:00
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(player));
        when(bookingPriceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(300_000L);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(start).durationHours(3).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        service.createBooking(USER_ID, req);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        Booking saved = captor.getValue();
        assertThat(saved.getDurationHours()).isEqualTo(3);
        assertThat(saved.getPrice()).isEqualTo(300_000);
        assertThat(saved.getEndsAt()).isEqualTo(start.plusHours(3));
    }

    @Test
    void createBookingRejectsUnapprovedOrInactiveStadium() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.empty());

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(startAt(18, 2)).durationHours(1).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBookingRejectsTheStadiumOwnerBookingTheirOwnPitch() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(startAt(18, 2)).durationHours(1).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        assertThatThrownBy(() -> service.createBooking(OWNER_ID, req))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("manual booking");

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBookingStillAllowsAPlayerWhoIsNotTheOwner() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(player));
        when(bookingPriceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(100_000L);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(startAt(18, 2)).durationHours(1).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        service.createBooking(USER_ID, req);

        verify(bookingRepository).saveAndFlush(any(Booking.class));
    }

    @Test
    void createBookingRejectsStartNotOnTheHour() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));

        OffsetDateTime start = LocalDate.now(TASHKENT).plusDays(2).atTime(18, 30).atZone(TASHKENT).toOffsetDateTime();
        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(start).durationHours(1).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Booking must start on the hour");

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBookingRejectsZeroDuration() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(startAt(18, 2)).durationHours(0).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Duration must be at least 1 hour");

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBookingRejectsEndingAfterClosingTime() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));

        // 22:00 + 2h would end at 00:00 the next day, past the 23:00 close.
        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(startAt(22, 2)).durationHours(2).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Booking must end by 23:00");

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBookingRejectsStartBeforeOpeningTime() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(startAt(7, 2)).durationHours(1).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Booking must start at or after 08:00");

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBookingRejectsSlotWithinClosure() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        LocalDate day = LocalDate.now(TASHKENT).plusDays(2);
        when(closureService.isClosedAt(eq(STADIUM_ID), eq(day), eq(LocalTime.of(18, 0)))).thenReturn(true);

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(startAt(18, 2)).durationHours(1).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Stadium is closed on " + day);

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBookingRejectsTooFarAhead() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(startAt(18, 35)).durationHours(1).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build(); // 5 weeks, default max is 4
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(BadRequestException.class);

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBookingTranslatesSingleHourOverlapToSlotAlreadyBooked() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(player));
        when(bookingPriceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(100_000L);
        when(bookingRepository.saveAndFlush(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("bookings_no_overlap"));

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(startAt(18, 2)).durationHours(1).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(SlotAlreadyBookedException.class)
                .hasMessage("Slot is already booked");
    }

    @Test
    void createBookingTranslatesMultiHourOverlapToClearerMessage() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(player));
        when(bookingPriceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(150_000L);
        when(bookingRepository.saveAndFlush(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("bookings_no_overlap"));

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(startAt(18, 2)).durationHours(3).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(SlotAlreadyBookedException.class)
                .hasMessage("One or more of the selected hours is already booked");
    }

    @Test
    void createBookingAsksGameServiceToClearWaitingGamesOnTheSlot() {
        OffsetDateTime start = startAt(18, 2);
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(player));
        when(bookingPriceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(100_000L);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(start).durationHours(2).cardId(CARD_ID).paymentOption(PaymentOption.FULL).build();
        service.createBooking(USER_ID, req);

        verify(gameService).cancelGamesOnSlot(STADIUM_ID, start, start.plusHours(2));
    }

    // ---- confirmBooking ----

    @Test
    void confirmBookingMovesPendingToConfirmed() {
        Booking booking = Booking.builder().id(BOOKING_ID).status("pending").expiresAt(inHours(1)).stadium(stadium).build();
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        service.confirmBooking(BOOKING_ID);

        assertThat(booking.getStatus()).isEqualTo("confirmed");
        assertThat(booking.getExpiresAt()).isNull();
    }

    @Test
    void confirmBookingRejectsExpired() {
        Booking booking = Booking.builder().id(BOOKING_ID).status("expired").stadium(stadium).build();
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.confirmBooking(BOOKING_ID)).isInstanceOf(BookingExpiredException.class);
        verify(bookingRepository, never()).save(any());
    }

    @Test
    void confirmBookingIsIdempotentWhenAlreadyConfirmed() {
        Booking booking = Booking.builder().id(BOOKING_ID).status("confirmed").stadium(stadium).build();
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        service.confirmBooking(BOOKING_ID);

        verify(bookingRepository, never()).save(any());
    }

    // ---- cancelByUser: cancellation policy ----

    private Booking confirmedBooking(OffsetDateTime start, long price) {
        return Booking.builder().id(BOOKING_ID).status("confirmed").user(player).stadium(stadium)
                .price(price).startsAt(start).build();
    }

    @Test
    void cancelByUserRefundsEveryCardPaymentWhenInTime() {
        Booking booking = confirmedBooking(inHours(48), 100_000); // >= 24h
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());
        when(paymentService.refundAllCardPayments(BOOKING_ID, "changed plans")).thenReturn(100_000L);

        BookingResponse resp = service.cancelByUser(USER_ID, BOOKING_ID, "changed plans");

        assertThat(booking.getStatus()).isEqualTo("cancelled_by_user");
        assertThat(booking.getCancelledAt()).isNotNull();
        assertThat(resp.getRefundAmount()).isEqualTo(100_000);
        verify(paymentService).refundAllCardPayments(BOOKING_ID, "changed plans");
    }

    @Test
    void cancellingExactlyOnTheDeadlineStillRefunds() {
        Booking booking = confirmedBooking(inHours(25), 100_000); // the 24h deadline is an hour off
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());
        when(paymentService.refundAllCardPayments(BOOKING_ID, null)).thenReturn(100_000L);

        BookingResponse resp = service.cancelByUser(USER_ID, BOOKING_ID, null);

        assertThat(resp.getRefundAmount()).isEqualTo(100_000);
    }

    @Test
    void cancelByUserRefundsNothingInsideTheWindow() {
        Booking booking = confirmedBooking(inHours(18), 100_000); // under 24h: no partial any more
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        BookingResponse resp = service.cancelByUser(USER_ID, BOOKING_ID, null);

        assertThat(booking.getStatus()).isEqualTo("cancelled_by_user");
        assertThat(resp.getRefundAmount()).isZero();
        verify(paymentService, never()).refundAllCardPayments(any(UUID.class), any());
    }

    @Test
    void cancelByUserNoRefundWhenTooLate() {
        Booking booking = confirmedBooking(inHours(2), 100_000);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        BookingResponse resp = service.cancelByUser(USER_ID, BOOKING_ID, null);

        assertThat(resp.getRefundAmount()).isZero();
        verify(paymentService, never()).refundAllCardPayments(any(UUID.class), any());
    }

    @Test
    void cancelByUserGivesNoRefundForUnpaidPendingHold() {
        Booking booking = Booking.builder().id(BOOKING_ID).status("pending").user(player).stadium(stadium)
                .price(100_000L).startsAt(inHours(48)).build();
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());
        // In time, so the refund runs — but an unpaid hold has no card payment,
        // so there is nothing to give back.
        when(paymentService.refundAllCardPayments(BOOKING_ID, null)).thenReturn(0L);

        BookingResponse resp = service.cancelByUser(USER_ID, BOOKING_ID, null);

        assertThat(booking.getStatus()).isEqualTo("cancelled_by_user");
        assertThat(resp.getRefundAmount()).isZero();
    }

    @Test
    void cancelByUserRejectsForeignBooking() {
        Booking booking = confirmedBooking(inHours(48), 100_000);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancelByUser(FOREIGN_USER_ID, BOOKING_ID, null)).isInstanceOf(ForbiddenException.class);
        verify(bookingRepository, never()).save(any());
    }

    // ---- cancelByOwner ----

    @Test
    void cancelByOwnerAlwaysRefundsFullEvenWhenLate() {
        Booking booking = Booking.builder().id(BOOKING_ID).status("confirmed").user(player).stadium(stadium)
                .price(80_000L).startsAt(inHours(1)).build(); // inside the window: a user would get 0
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());
        when(paymentService.refundAllCardPayments(BOOKING_ID, "pitch flooded")).thenReturn(80_000L);

        BookingResponse resp = service.cancelByOwner(OWNER_ID, BOOKING_ID, "pitch flooded");

        assertThat(booking.getStatus()).isEqualTo("cancelled_by_owner");
        assertThat(resp.getRefundAmount()).isEqualTo(80_000);
        verify(paymentService).refundAllCardPayments(BOOKING_ID, "pitch flooded");
    }

    @Test
    void cancelByOwnerSendsAGameBookingThroughTheGame() {
        UUID gameId = UUID.randomUUID();
        Booking booking = Booking.builder().id(BOOKING_ID).status("confirmed").user(player).stadium(stadium)
                .source("game").gameId(gameId).price(80_000L).startsAt(inHours(48)).build();
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());
        when(gameService.cancelGameForBooking(eq(gameId), anyString())).thenReturn(true);

        service.cancelByOwner(OWNER_ID, BOOKING_ID, "pitch flooded");

        // A team game's money hangs off game_teams; refunding the booking's own
        // payments would hand back nothing and hide the real refund.
        verify(gameService).cancelGameForBooking(eq(gameId), anyString());
        verify(paymentService, never()).refundAllCardPayments(eq(BOOKING_ID), anyString());
        assertThat(booking.getStatus()).isEqualTo("cancelled_by_owner");
    }

    @Test
    void cancelByOwnerRejectsNonOwner() {
        Booking booking = confirmedBooking(inHours(48), 100_000);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));

        assertThatThrownBy(() -> service.cancelByOwner(NON_OWNER_ID, BOOKING_ID, null)).isInstanceOf(ForbiddenException.class);
    }

    // ---- listing ----

    @Test
    void getMyBookingsWrapsPage() {
        Booking booking = confirmedBooking(inHours(48), 100_000);
        when(bookingRepository.findByUserId(eq(USER_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(booking)));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        PageResponse<BookingResponse> result = service.getMyBookings(USER_ID, 0, 20);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getTotalElements()).isEqualTo(1);
    }

    @Test
    void getStadiumBookingsReturnsOwnerSchedule() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(bookingRepository.findByStadiumId(eq(STADIUM_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(confirmedBooking(inHours(48), 100_000))));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        PageResponse<BookingResponse> result = service.getStadiumBookings(OWNER_ID, STADIUM_ID, null, null, 0, 20);

        assertThat(result.getContent()).hasSize(1);
    }

    // ---- manual bookings ----

    private CreateManualBookingRequest manualRequest(int tashkentHour, int duration) {
        return CreateManualBookingRequest.builder()
                .stadiumId(STADIUM_ID).clientName("Walk-in").clientPhone("+998901112233")
                .startsAt(startAt(tashkentHour, 1)).duration(duration).build();
    }

    @Test
    void createManualBookingConfirmsImmediatelyAndRecordsCash() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(bookingPriceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(100_000L);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(MANUAL_BOOKING_ID);
            return b;
        });
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        service.createManualBooking(manualRequest(18, 1), OWNER_ID);

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        Booking saved = captor.getValue();
        assertThat(saved.getStatus()).isEqualTo("confirmed");
        assertThat(saved.getSource()).isEqualTo("manual");
        assertThat(saved.isWalkin()).isTrue();
        assertThat(saved.getUser()).isNull();
        assertThat(saved.getClientName()).isEqualTo("Walk-in");
        verify(gameService).cancelGamesOnSlot(eq(STADIUM_ID), any(), any());
        verify(paymentService).recordCashPayment(MANUAL_BOOKING_ID, OWNER_ID);
    }

    @Test
    void createManualBookingRejectsForeignOwner() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));

        assertThatThrownBy(() -> service.createManualBooking(manualRequest(18, 1), FOREIGN_USER_ID))
                .isInstanceOf(ForbiddenException.class);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createManualBookingEnforcesOpeningHours() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));

        // 22:00 + 2h ends at 00:00, past the 23:00 close.
        assertThatThrownBy(() -> service.createManualBooking(manualRequest(22, 2), OWNER_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Booking must end by 23:00");
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createManualBookingEnforcesClosures() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        LocalDate day = LocalDate.now(TASHKENT).plusDays(1);
        when(closureService.isClosedAt(eq(STADIUM_ID), eq(day), eq(LocalTime.of(18, 0)))).thenReturn(true);

        assertThatThrownBy(() -> service.createManualBooking(manualRequest(18, 1), OWNER_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Stadium is closed on " + day);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createManualBookingTranslatesMultiHourOverlapToClearerMessage() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(bookingPriceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(150_000L);
        when(bookingRepository.saveAndFlush(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("bookings_no_overlap"));

        assertThatThrownBy(() -> service.createManualBooking(manualRequest(18, 3), OWNER_ID))
                .isInstanceOf(SlotAlreadyBookedException.class)
                .hasMessage("One or more of the selected hours is already booked");
    }
}
