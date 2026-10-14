package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.dto.response.PaymentInitResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.ClickProvider;
import uz.minipolya.minipolya_backend.provider.PaymeProvider;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentServiceTest {

    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID PAYMENT_ID = UUID.randomUUID();

    private PaymentRepository paymentRepository;
    private BookingRepository bookingRepository;
    private UserRepository userRepository;
    private GameRepository gameRepository;
    private GameParticipantRepository gameParticipantRepository;
    private PaymentMapper paymentMapper;
    private GameService gameService;
    private NotificationService notificationService;
    private AuditService auditService;
    private PaymentService service;

    private User player;
    private User owner;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        bookingRepository = mock(BookingRepository.class);
        userRepository = mock(UserRepository.class);
        gameRepository = mock(GameRepository.class);
        gameParticipantRepository = mock(GameParticipantRepository.class);
        paymentMapper = mock(PaymentMapper.class);
        gameService = mock(GameService.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);

        service = new PaymentService(paymentRepository, bookingRepository, userRepository,
                gameRepository, gameParticipantRepository, paymentMapper,
                List.of(new PaymeProvider(), new ClickProvider()), gameService, notificationService, auditService);

        player = User.builder().id(PLAYER_ID).name("Player").build();
        owner = User.builder().id(OWNER_ID).name("Owner").build();
        stadium = Stadium.builder().id(STADIUM_ID).owner(owner).build();
    }

    private Booking pendingBooking() {
        return Booking.builder().id(BOOKING_ID).status("pending").user(player).stadium(stadium).price(100_000).build();
    }

    // ---- initiateBookingPayment ----

    @Test
    void initiateReturnsCheckoutUrlForValidPendingBooking() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(pendingBooking()));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(PAYMENT_ID);
            return p;
        });
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        PaymentInitResponse resp = service.initiateBookingPayment(BOOKING_ID, "payme", PLAYER_ID);

        assertThat(resp.getPaymentId()).isEqualTo(PAYMENT_ID);
        assertThat(resp.getProvider()).isEqualTo("payme");
        assertThat(resp.getCheckoutUrl()).isEqualTo("https://checkout.paycom.uz/mock/" + PAYMENT_ID);
    }

    @Test
    void initiateThrowsWhenBookingMissing() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.initiateBookingPayment(BOOKING_ID, "payme", PLAYER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void initiateRejectsBookingOwnedByAnotherUser() {
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(pendingBooking()));

        assertThatThrownBy(() -> service.initiateBookingPayment(BOOKING_ID, "payme", UUID.randomUUID()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ---- handleProviderCallback ----

    @Test
    void callbackCompletesPaymentAndConfirmsBooking() {
        Booking booking = pendingBooking();
        booking.setExpiresAt(java.time.OffsetDateTime.now());
        Payment payment = Payment.builder().id(PAYMENT_ID).booking(booking).user(player).amount(100_000)
                .paymentType(Payment.PaymentType.BOOKING).provider(Payment.Provider.PAYME)
                .providerTxnId("mock-100").status(Payment.Status.PENDING).build();
        when(paymentRepository.findByProviderAndProviderTxnId(Payment.Provider.PAYME, "mock-100"))
                .thenReturn(Optional.of(payment));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.handleProviderCallback("payme", "mock-100", "completed");

        assertThat(result.getStatus()).isEqualTo(Payment.Status.COMPLETED);
        assertThat(booking.getStatus()).isEqualTo("confirmed");
        assertThat(booking.getExpiresAt()).isNull();
    }

    @Test
    void callbackIsIdempotentForAnAlreadyCompletedPayment() {
        Payment payment = Payment.builder().id(PAYMENT_ID).provider(Payment.Provider.PAYME)
                .providerTxnId("mock-100").status(Payment.Status.COMPLETED).build();
        when(paymentRepository.findByProviderAndProviderTxnId(Payment.Provider.PAYME, "mock-100"))
                .thenReturn(Optional.of(payment));

        Payment result = service.handleProviderCallback("payme", "mock-100", "completed");

        assertThat(result.getStatus()).isEqualTo(Payment.Status.COMPLETED);
        verify(paymentRepository, never()).save(any());
        verify(bookingRepository, never()).save(any());
    }

    // ---- createRefund ----

    @Test
    void createRefundAddsNegativeRowAndMarksOriginalRefunded() {
        Booking booking = pendingBooking();
        Payment original = Payment.builder().id(PAYMENT_ID).booking(booking).user(player).amount(100_000)
                .paymentType(Payment.PaymentType.BOOKING).provider(Payment.Provider.PAYME)
                .status(Payment.Status.COMPLETED).build();
        when(paymentRepository.findById(PAYMENT_ID)).thenReturn(Optional.of(original));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment refund = service.createRefund(PAYMENT_ID, 50_000, "user cancelled");

        assertThat(refund.getAmount()).isEqualTo(-50_000);
        assertThat(refund.getPaymentType()).isEqualTo(Payment.PaymentType.REFUND);
        assertThat(refund.getStatus()).isEqualTo(Payment.Status.COMPLETED);
        assertThat(refund.getRefundOfPayment()).isSameAs(original);
        assertThat(refund.getRefundReason()).isEqualTo("user cancelled");
        assertThat(original.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    // ---- recordCashPayment ----

    @Test
    void recordCashPaymentCompletesImmediatelyAndConfirmsBooking() {
        Booking booking = Booking.builder().id(BOOKING_ID).status("pending").stadium(stadium).price(70_000).build();
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(booking));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));

        Payment result = service.recordCashPayment(BOOKING_ID, OWNER_ID);

        assertThat(result.getProvider()).isEqualTo(Payment.Provider.CASH);
        assertThat(result.getStatus()).isEqualTo(Payment.Status.COMPLETED);
        assertThat(result.getAmount()).isEqualTo(70_000);
        assertThat(result.getUser()).isSameAs(owner);
        assertThat(booking.getStatus()).isEqualTo("confirmed");
    }
}
