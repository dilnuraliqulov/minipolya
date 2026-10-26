package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.request.CreateBookingRequest;
import uz.minipolya.minipolya_backend.dto.request.PaymentOption;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

/**
 * Paying for an ordinary booking: the player pays either the stadium's minimum
 * or the full price, nothing else.
 */
class BookingPaymentTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID CARD_ID = UUID.randomUUID();
    private static final long PRICE = 10_000_000L;      // 100 000 so'm
    private static final long MINIMUM = 3_000_000L;      // 30 000 so'm

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
    private Stadium stadium;
    private SavedCard card;

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

        service = new BookingService(bookingRepository, stadiumRepository, userRepository,
                bookingPriceCalculator, new CancellationPolicy(),
                new BookingResponseFactory(bookingMapper, new CancellationPolicy(), paymentService),
                new AppProperties(),
                paymentService, gameService, notificationService, auditService,
                new BookingWindowValidator(closureService), savedCardService);

        player = User.builder().id(USER_ID).name("Player").build();
        stadium = Stadium.builder().id(STADIUM_ID).status("approved").isActive(true).pricePerHour(5_000_000L)
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0))
                .minBookingAmount(MINIMUM)
                .owner(User.builder().id(OWNER_ID).name("Owner").build()).build();
        card = SavedCard.builder().id(CARD_ID).user(player).providerToken("tok-1")
                .maskedNumber("8600 •••• 1234").isActive(true).build();

        when(stadiumRepository.findByIdAndStatus(STADIUM_ID, "approved")).thenReturn(Optional.of(stadium));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(player));
        when(bookingPriceCalculator.totalPrice(any(Stadium.class), any(OffsetDateTime.class), anyInt()))
                .thenReturn(PRICE);
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());
        when(savedCardService.requireOwnedCard(USER_ID, CARD_ID)).thenReturn(card);
    }

    private OffsetDateTime startAt(int tashkentHour, long daysAhead) {
        return LocalDate.now(TASHKENT).plusDays(daysAhead).atTime(tashkentHour, 0)
                .atZone(TASHKENT).toOffsetDateTime();
    }

    private CreateBookingRequest request(PaymentOption option) {
        return CreateBookingRequest.builder()
                .stadiumId(STADIUM_ID)
                .startsAt(startAt(18, 2))
                .durationHours(2)
                .cardId(CARD_ID)
                .paymentOption(option)
                .build();
    }

    @Test
    void fullPaymentChargesTheWholePrice() {
        service.createBooking(USER_ID, request(PaymentOption.FULL));

        verify(paymentService).chargeForBooking(any(Booking.class), eq(card), eq(PRICE));
    }

    @Test
    void minimumPaymentChargesTheStadiumMinimum() {
        service.createBooking(USER_ID, request(PaymentOption.MINIMUM));

        verify(paymentService).chargeForBooking(any(Booking.class), eq(card), eq(MINIMUM));
    }

    @Test
    void minimumIsRejectedWhenTheStadiumHasNoMinimum() {
        stadium.setMinBookingAmount(null);

        assertThatThrownBy(() -> service.createBooking(USER_ID, request(PaymentOption.MINIMUM)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("full payment");

        verify(bookingRepository, never()).saveAndFlush(any());
        verify(paymentService, never()).chargeForBooking(any(), any(), anyLong());
    }

    @Test
    void minimumIsRejectedWhenItIsNotBelowTheBookingPrice() {
        // A minimum at or above the price leaves nothing to pay later, so only FULL makes sense.
        stadium.setMinBookingAmount(PRICE);

        assertThatThrownBy(() -> service.createBooking(USER_ID, request(PaymentOption.MINIMUM)))
                .isInstanceOf(BadRequestException.class);

        verify(paymentService, never()).chargeForBooking(any(), any(), anyLong());
    }

    @Test
    void theBookingStartsPendingAndUnpaidBeforeTheCharge() {
        service.createBooking(USER_ID, request(PaymentOption.FULL));

        ArgumentCaptor<Booking> captor = ArgumentCaptor.forClass(Booking.class);
        verify(bookingRepository).saveAndFlush(captor.capture());
        Booking saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo("pending");
        assertThat(saved.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.UNPAID);
        assertThat(saved.getPaidAmount()).isZero();
        assertThat(saved.getPrice()).isEqualTo(PRICE);
        assertThat(saved.getExpiresAt()).isNotNull();
    }

    @Test
    void theCardMustBelongToTheBookingPlayer() {
        when(savedCardService.requireOwnedCard(USER_ID, CARD_ID))
                .thenThrow(new BadRequestException("Card not found"));

        assertThatThrownBy(() -> service.createBooking(USER_ID, request(PaymentOption.FULL)))
                .isInstanceOf(BadRequestException.class);

        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    void remainingAmountIsPriceMinusWhatHasBeenPaid() {
        Booking booking = Booking.builder().price(PRICE).paidAmount(MINIMUM).build();

        assertThat(booking.getRemainingAmount()).isEqualTo(PRICE - MINIMUM);
    }

    @Test
    void remainingAmountNeverGoesNegative() {
        Booking booking = Booking.builder().price(PRICE).paidAmount(PRICE + 1_000L).build();

        assertThat(booking.getRemainingAmount()).isZero();
    }

    private static long anyLong() {
        return org.mockito.ArgumentMatchers.anyLong();
    }
}
