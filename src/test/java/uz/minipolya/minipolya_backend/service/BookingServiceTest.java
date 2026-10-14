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
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.dto.response.PageResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.BookingExpiredException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.exception.SlotAlreadyBookedException;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;
import uz.minipolya.minipolya_backend.util.RefundCalculator;

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

class BookingServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID FOREIGN_USER_ID = UUID.randomUUID();
    private static final UUID NON_OWNER_ID = UUID.randomUUID();
    private static final UUID MANUAL_BOOKING_ID = UUID.randomUUID();

    private BookingRepository bookingRepository;
    private StadiumRepository stadiumRepository;
    private UserRepository userRepository;
    private PricingRuleRepository pricingRuleRepository;
    private PriceCalculator priceCalculator;
    private BookingMapper bookingMapper;
    private PaymentService paymentService;
    private GameService gameService;
    private NotificationService notificationService;
    private AuditService auditService;
    private BookingService service;

    private User player;
    private User owner;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        stadiumRepository = mock(StadiumRepository.class);
        userRepository = mock(UserRepository.class);
        pricingRuleRepository = mock(PricingRuleRepository.class);
        priceCalculator = mock(PriceCalculator.class);
        bookingMapper = mock(BookingMapper.class);
        paymentService = mock(PaymentService.class);
        gameService = mock(GameService.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);

        service = new BookingService(bookingRepository, stadiumRepository, userRepository,
                pricingRuleRepository, priceCalculator, new RefundCalculator(),
                bookingMapper, new AppProperties(), paymentService, gameService, notificationService, auditService);

        player = User.builder().id(USER_ID).name("Player").build();
        owner = User.builder().id(OWNER_ID).name("Owner").build();
        stadium = Stadium.builder().id(STADIUM_ID).status("approved").isActive(true).pricePerHour(50_000)
                .fullRefundHours(24).partialRefundHours(12).partialRefundPercent(50).owner(owner).build();
    }

    private OffsetDateTime inHours(long h) {
        return OffsetDateTime.now(ZoneOffset.UTC).plusHours(h);
    }

    // ---- createBooking ----

    @Test
    void createBookingHoldsSlotAsPending() {
        OffsetDateTime start = inHours(48);
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(player));
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(100_000);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(start).durationHours(2).build();
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
    void createBookingRejectsUnapprovedOrInactiveStadium() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.empty());

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(inHours(48)).durationHours(1).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBookingRejectsTooFarAhead() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));

        OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusWeeks(5); // default max is 4
        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(start).durationHours(1).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(BadRequestException.class);

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createBookingTranslatesOverlapConstraintToSlotAlreadyBooked() {
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(player));
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(100_000);
        when(bookingRepository.saveAndFlush(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("bookings_no_overlap"));

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(inHours(48)).durationHours(1).build();
        assertThatThrownBy(() -> service.createBooking(USER_ID, req))
                .isInstanceOf(SlotAlreadyBookedException.class);
    }

    @Test
    void createBookingAsksGameServiceToClearWaitingGamesOnTheSlot() {
        OffsetDateTime start = inHours(48);
        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(player));
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(100_000);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        CreateBookingRequest req = CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID).startsAt(start).durationHours(2).build();
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

    private Booking confirmedBooking(OffsetDateTime start, int price) {
        return Booking.builder().id(BOOKING_ID).status("confirmed").user(player).stadium(stadium)
                .price(price).startsAt(start).build();
    }

    @Test
    void cancelByUserFullRefundWhenWellAhead() {
        Booking booking = confirmedBooking(inHours(48), 100_000); // >= 24h
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        BookingResponse resp = service.cancelByUser(USER_ID, BOOKING_ID, "changed plans");

        assertThat(booking.getStatus()).isEqualTo("cancelled_by_user");
        assertThat(booking.getCancelledAt()).isNotNull();
        assertThat(resp.getRefundAmount()).isEqualTo(100_000);
        verify(paymentService).refundBookingPayment(BOOKING_ID, 100_000, "changed plans");
    }

    @Test
    void cancelByUserPartialRefundInsidePartialWindow() {
        Booking booking = confirmedBooking(inHours(18), 100_000); // >=12 and <24
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        BookingResponse resp = service.cancelByUser(USER_ID, BOOKING_ID, null);

        assertThat(resp.getRefundAmount()).isEqualTo(50_000); // 50%
        verify(paymentService).refundBookingPayment(BOOKING_ID, 50_000, null);
    }

    @Test
    void cancelByUserNoRefundWhenTooLate() {
        Booking booking = confirmedBooking(inHours(2), 100_000); // < 12h
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        BookingResponse resp = service.cancelByUser(USER_ID, BOOKING_ID, null);

        assertThat(resp.getRefundAmount()).isZero();
        verify(paymentService, never()).refundBookingPayment(any(UUID.class), anyInt(), any());
    }

    @Test
    void cancelByUserGivesNoRefundForUnpaidPendingHold() {
        Booking booking = Booking.builder().id(BOOKING_ID).status("pending").user(player).stadium(stadium)
                .price(100_000).startsAt(inHours(48)).build();
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        BookingResponse resp = service.cancelByUser(USER_ID, BOOKING_ID, null);

        assertThat(booking.getStatus()).isEqualTo("cancelled_by_user");
        assertThat(resp.getRefundAmount()).isZero();
        verify(paymentService, never()).refundBookingPayment(any(UUID.class), anyInt(), any());
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
                .price(80_000).startsAt(inHours(1)).build(); // < 12h: a user would get 0
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        BookingResponse resp = service.cancelByOwner(OWNER_ID, BOOKING_ID, "pitch flooded");

        assertThat(booking.getStatus()).isEqualTo("cancelled_by_owner");
        assertThat(resp.getRefundAmount()).isEqualTo(80_000);
        verify(paymentService).refundBookingPayment(BOOKING_ID, 80_000, "pitch flooded");
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

    private CreateManualBookingRequest manualRequest() {
        return CreateManualBookingRequest.builder()
                .stadiumId(STADIUM_ID).clientName("Walk-in").clientPhone("+998901112233")
                .startsAt(inHours(5)).duration(1).build();
    }

    @Test
    void createManualBookingConfirmsImmediatelyAndRecordsCash() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(100_000);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> {
            Booking b = inv.getArgument(0);
            b.setId(MANUAL_BOOKING_ID);
            return b;
        });
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());

        service.createManualBooking(manualRequest(), OWNER_ID);

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

        assertThatThrownBy(() -> service.createManualBooking(manualRequest(), FOREIGN_USER_ID))
                .isInstanceOf(ForbiddenException.class);
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void createManualBookingTranslatesOverlapToSlotAlreadyBooked() {
        when(stadiumRepository.findById(STADIUM_ID)).thenReturn(Optional.of(stadium));
        when(priceCalculator.calculateTotalPrice(any(), any(), any(), anyInt(), anyInt())).thenReturn(100_000);
        when(bookingRepository.saveAndFlush(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("bookings_no_overlap"));

        assertThatThrownBy(() -> service.createManualBooking(manualRequest(), OWNER_ID))
                .isInstanceOf(SlotAlreadyBookedException.class);
    }
}
