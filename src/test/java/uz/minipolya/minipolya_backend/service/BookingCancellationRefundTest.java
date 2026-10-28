package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.mapper.BookingMapper;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.CancellationPolicy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cancellation refunds are all-or-nothing and always for whole payments. What a
 * booking response promises in {@code refundIfCancelledNow} has to be exactly
 * what cancelling gives back, so both are checked against the same payments.
 */
class BookingCancellationRefundTest {

    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID USER_ID = UUID.randomUUID();
    private static final long MINIMUM = 3_000_000L;
    private static final long REMAINDER = 7_000_000L;

    private PaymentRepository paymentRepository;
    private PaymentProvider paymentProvider;
    private PaymentService paymentService;
    private BookingResponseFactory responseFactory;
    private final CancellationPolicy policy = new CancellationPolicy();

    private User player;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        paymentProvider = mock(PaymentProvider.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.current()).thenReturn(PlatformSettings.builder().build());

        paymentService = new PaymentService(paymentRepository, mock(BookingRepository.class),
                mock(UserRepository.class), mock(GameRepository.class), mock(GameParticipantRepository.class),
                mock(PaymentMapper.class), List.of(), mock(GameService.class),
                mock(NotificationService.class), mock(AuditService.class), paymentProvider, settings);

        BookingMapper bookingMapper = mock(BookingMapper.class);
        when(bookingMapper.toResponse(any(Booking.class))).thenAnswer(inv -> new BookingResponse());
        responseFactory = new BookingResponseFactory(bookingMapper, policy, paymentService, CardPricing.withDefaults());

        when(paymentRepository.save(any(Payment.class))).thenAnswer(ProviderStubs.savedWithId());

        player = User.builder().id(USER_ID).name("Player").build();
        stadium = Stadium.builder().id(UUID.randomUUID()).name("Arena").cancellationHours(24).build();
    }

    private Booking bookingStartingIn(long hours) {
        return Booking.builder()
                .id(BOOKING_ID).stadium(stadium).user(player).status("confirmed")
                .price(MINIMUM + REMAINDER).paidAmount(MINIMUM + REMAINDER)
                .paymentStatus(Booking.PaymentStatus.PAID)
                .startsAt(OffsetDateTime.now(ZoneOffset.UTC).plusHours(hours))
                .build();
    }

    private Payment card(long amount, Payment.Status status) {
        return Payment.builder().id(UUID.randomUUID()).user(player).amount(amount)
                .ownerAmount(amount).platformAmount(0L).commissionPercent(0)
                .method(Payment.Method.CARD).provider(Payment.Provider.ATMOS)
                .providerTxnId("txn-" + amount).paymentType(Payment.PaymentType.BOOKING)
                .status(status).build();
    }

    private Payment cash(long amount) {
        return Payment.builder().id(UUID.randomUUID()).user(player).amount(amount)
                .ownerAmount(amount).platformAmount(0L).commissionPercent(0)
                .method(Payment.Method.CASH).provider(Payment.Provider.CASH)
                .paymentType(Payment.PaymentType.BOOKING).status(Payment.Status.PAID).build();
    }

    @Test
    void theTotalCoversEveryCardPaymentOfTheBooking() {
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(
                card(MINIMUM, Payment.Status.PAID), card(REMAINDER, Payment.Status.PAID)));

        assertThat(paymentService.refundableCardTotal(BOOKING_ID)).isEqualTo(MINIMUM + REMAINDER);
    }

    @Test
    void cashIsLeftOutOfTheRefundableTotal() {
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(
                card(MINIMUM, Payment.Status.PAID), cash(REMAINDER)));

        assertThat(paymentService.refundableCardTotal(BOOKING_ID)).isEqualTo(MINIMUM);
    }

    @Test
    void failedAndAlreadyRefundedRowsAreLeftOut() {
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(
                card(MINIMUM, Payment.Status.PAID),
                card(REMAINDER, Payment.Status.FAILED),
                card(REMAINDER, Payment.Status.REFUNDED)));

        assertThat(paymentService.refundableCardTotal(BOOKING_ID)).isEqualTo(MINIMUM);
    }

    @Test
    void refundingTheBookingGivesBackEveryCardPaymentWhole() {
        Payment first = card(MINIMUM, Payment.Status.PAID);
        Payment second = card(REMAINDER, Payment.Status.PAID);
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(first, second));

        long refunded = paymentService.refundAllCardPayments(BOOKING_ID, "cancelled");

        assertThat(refunded).isEqualTo(MINIMUM + REMAINDER);
        assertThat(first.getStatus()).isEqualTo(Payment.Status.REFUNDED);
        assertThat(second.getStatus()).isEqualTo(Payment.Status.REFUNDED);
        // Each transaction is reversed in full; reversal is the only refund ATMOS has.
        verify(paymentProvider).reverse("txn-" + MINIMUM, "cancelled");
        verify(paymentProvider).reverse("txn-" + REMAINDER, "cancelled");
    }

    @Test
    void cashIsNotGivenBackWhenTheBookingIsRefunded() {
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(
                card(MINIMUM, Payment.Status.PAID), cash(REMAINDER)));

        assertThat(paymentService.refundAllCardPayments(BOOKING_ID, "cancelled")).isEqualTo(MINIMUM);
    }

    // ---- what the response promises is what cancelling pays ----

    @Test
    void theResponseCarriesTheDeadlineAndTheRefundDueNow() {
        Booking booking = bookingStartingIn(48);
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(
                card(MINIMUM, Payment.Status.PAID), card(REMAINDER, Payment.Status.PAID)));

        BookingResponse response = responseFactory.of(booking);

        assertThat(response.getCancellationDeadline()).isEqualTo(booking.getStartsAt().minusHours(24));
        assertThat(response.getRefundIfCancelledNow()).isEqualTo(MINIMUM + REMAINDER);
    }

    @Test
    void theResponsePromisesNothingOnceTheWindowHasClosed() {
        Booking booking = bookingStartingIn(3);
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(
                card(MINIMUM, Payment.Status.PAID), card(REMAINDER, Payment.Status.PAID)));

        BookingResponse response = responseFactory.of(booking);

        assertThat(response.getRefundIfCancelledNow()).isZero();
    }

    @Test
    void thePromisedRefundIsExactlyWhatCancellingPaysOut() {
        Booking booking = bookingStartingIn(48);
        when(paymentRepository.findByBookingId(BOOKING_ID)).thenReturn(List.of(
                card(MINIMUM, Payment.Status.PAID), cash(REMAINDER)));

        long promised = responseFactory.of(booking).getRefundIfCancelledNow();
        long actuallyRefunded = paymentService.refundAllCardPayments(BOOKING_ID, "cancelled");

        assertThat(promised).isEqualTo(actuallyRefunded);
    }
}
