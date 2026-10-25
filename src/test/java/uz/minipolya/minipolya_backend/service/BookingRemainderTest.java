package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.RefundCalculator;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Paying off the rest of a partially paid booking from a saved card. The owner
 * can take the same money in cash instead; whichever lands first wins and the
 * other is refused.
 */
class BookingRemainderTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID CARD_ID = UUID.randomUUID();
    private static final long PRICE = 10_000_000L;      // 100 000 so'm
    private static final long PAID = 3_000_000L;        // the minimum already taken
    private static final long REMAINDER = PRICE - PAID;

    private BookingRepository bookingRepository;
    private BookingMapper bookingMapper;
    private PaymentService paymentService;
    private SavedCardService savedCardService;
    private AuditService auditService;
    private BookingService service;

    private User player;
    private Stadium stadium;
    private SavedCard card;

    @BeforeEach
    void setUp() {
        bookingRepository = mock(BookingRepository.class);
        bookingMapper = mock(BookingMapper.class);
        paymentService = mock(PaymentService.class);
        savedCardService = mock(SavedCardService.class);
        auditService = mock(AuditService.class);

        service = new BookingService(bookingRepository, mock(StadiumRepository.class),
                mock(UserRepository.class), mock(BookingPriceCalculator.class), new RefundCalculator(),
                bookingMapper, new AppProperties(), paymentService, mock(GameService.class),
                mock(NotificationService.class), auditService,
                new BookingWindowValidator(mock(StadiumClosureService.class)), savedCardService);

        player = User.builder().id(USER_ID).name("Player").build();
        stadium = Stadium.builder().id(STADIUM_ID).name("Arena").build();
        card = SavedCard.builder().id(CARD_ID).user(player).providerToken("tok-1").isActive(true).build();

        when(savedCardService.requireOwnedCard(USER_ID, CARD_ID)).thenReturn(card);
        when(bookingMapper.toResponse(any(Booking.class))).thenReturn(new BookingResponse());
    }

    private Booking booking(String status, Booking.PaymentStatus paymentStatus, long paidAmount) {
        Booking booking = Booking.builder()
                .id(BOOKING_ID).stadium(stadium).user(player).status(status)
                .price(PRICE).paidAmount(paidAmount).paymentStatus(paymentStatus)
                .startsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2))
                .endsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).plusHours(2))
                .build();
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        return booking;
    }

    private void chargeSucceeds(Booking booking) {
        when(paymentService.chargeForBooking(any(Booking.class), any(SavedCard.class), anyLong(),
                eq(Payment.PaymentType.REMAINDER)))
                .thenAnswer(inv -> {
                    booking.setPaidAmount(PRICE);
                    booking.setPaymentStatus(Booking.PaymentStatus.PAID);
                    return Payment.builder().amount(REMAINDER).status(Payment.Status.PAID).build();
                });
    }

    @Test
    void chargesExactlyWhatIsStillOwed() {
        Booking booking = booking("confirmed", Booking.PaymentStatus.PARTIALLY_PAID, PAID);
        chargeSucceeds(booking);

        service.payRemainder(USER_ID, BOOKING_ID, CARD_ID);

        verify(paymentService).chargeForBooking(booking, card, REMAINDER, Payment.PaymentType.REMAINDER);
    }

    @Test
    void theRemainderCanBePaidAfterTheGameHasBeenPlayed() {
        Booking booking = booking("completed", Booking.PaymentStatus.PARTIALLY_PAID, PAID);
        chargeSucceeds(booking);

        service.payRemainder(USER_ID, BOOKING_ID, CARD_ID);

        verify(paymentService).chargeForBooking(booking, card, REMAINDER, Payment.PaymentType.REMAINDER);
    }

    @Test
    void aFullyPaidBookingIsRefused() {
        booking("confirmed", Booking.PaymentStatus.PAID, PRICE);

        assertThatThrownBy(() -> service.payRemainder(USER_ID, BOOKING_ID, CARD_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Already paid");

        verify(paymentService, never()).chargeForBooking(any(), any(), anyLong(), any());
    }

    @Test
    void aBookingThatWasNeverPaidIsRefused() {
        booking("pending", Booking.PaymentStatus.UNPAID, 0L);

        assertThatThrownBy(() -> service.payRemainder(USER_ID, BOOKING_ID, CARD_ID))
                .isInstanceOf(BadRequestException.class);

        verify(paymentService, never()).chargeForBooking(any(), any(), anyLong(), any());
    }

    @Test
    void aCancelledBookingIsRefused() {
        booking("cancelled_by_user", Booking.PaymentStatus.PARTIALLY_PAID, PAID);

        assertThatThrownBy(() -> service.payRemainder(USER_ID, BOOKING_ID, CARD_ID))
                .isInstanceOf(BadRequestException.class);

        verify(paymentService, never()).chargeForBooking(any(), any(), anyLong(), any());
    }

    @Test
    void someoneElsesBookingIsRefused() {
        booking("confirmed", Booking.PaymentStatus.PARTIALLY_PAID, PAID);

        assertThatThrownBy(() -> service.payRemainder(OTHER_USER_ID, BOOKING_ID, CARD_ID))
                .isInstanceOf(ForbiddenException.class);

        verify(paymentService, never()).chargeForBooking(any(), any(), anyLong(), any());
    }

    @Test
    void aDeclinedCardLeavesTheBookingPartiallyPaid() {
        Booking booking = booking("confirmed", Booking.PaymentStatus.PARTIALLY_PAID, PAID);
        when(paymentService.chargeForBooking(any(), any(), anyLong(), eq(Payment.PaymentType.REMAINDER)))
                .thenReturn(Payment.builder().amount(REMAINDER).status(Payment.Status.FAILED).build());

        service.payRemainder(USER_ID, BOOKING_ID, CARD_ID);

        assertThat(booking.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.PARTIALLY_PAID);
        assertThat(booking.getPaidAmount()).isEqualTo(PAID);
    }

    @Test
    void payingTheRemainderIsAudited() {
        Booking booking = booking("confirmed", Booking.PaymentStatus.PARTIALLY_PAID, PAID);
        chargeSucceeds(booking);

        service.payRemainder(USER_ID, BOOKING_ID, CARD_ID);

        verify(auditService).log(eq(USER_ID), eq("BOOKING_REMAINDER_PAID"), eq("booking"), eq(BOOKING_ID),
                any(String.class));
    }
}
