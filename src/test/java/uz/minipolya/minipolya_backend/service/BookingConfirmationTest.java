package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditActorType;
import uz.minipolya.minipolya_backend.audit.AuditChange;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.Audited;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Notification;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.ProviderTransaction;
import uz.minipolya.minipolya_backend.provider.ProviderTransactionStatus;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The single path from "money arrived" to "booking confirmed", including what
 * happens when the hold had already expired by then.
 */
class BookingConfirmationTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID STADIUM_ID = UUID.randomUUID();
    private static final UUID BOOKING_ID = UUID.randomUUID();
    private static final UUID CARD_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final long PRICE = 10_000_000L;
    private static final long MINIMUM = 3_000_000L;

    private PaymentRepository paymentRepository;
    private BookingRepository bookingRepository;
    private UserRepository userRepository;
    private NotificationService notificationService;
    private AuditService auditService;
    private PaymentProvider paymentProvider;
    private PlatformSettingsService platformSettingsService;
    private PaymentService service;

    private User player;
    private User owner;
    private Stadium stadium;
    private SavedCard card;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        bookingRepository = mock(BookingRepository.class);
        userRepository = mock(UserRepository.class);
        notificationService = mock(NotificationService.class);
        auditService = mock(AuditService.class);
        paymentProvider = mock(PaymentProvider.class);
        platformSettingsService = mock(PlatformSettingsService.class);

        service = new PaymentService(paymentRepository, bookingRepository, userRepository,
                mock(GameRepository.class), mock(GameParticipantRepository.class), mock(PaymentMapper.class),
                List.of(), mock(GameService.class), notificationService, auditService,
                paymentProvider, platformSettingsService);

        player = User.builder().id(USER_ID).name("Player").build();
        owner = User.builder().id(OWNER_ID).name("Owner").build();
        stadium = Stadium.builder().id(STADIUM_ID).name("Arena").owner(owner).build();
        card = SavedCard.builder().id(CARD_ID).user(player).providerToken("tok-1").isActive(true).build();

        when(platformSettingsService.current()).thenReturn(PlatformSettings.builder().build());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        when(bookingRepository.save(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Booking booking(String status) {
        return Booking.builder()
                .id(BOOKING_ID).stadium(stadium).user(player).status(status)
                .price(PRICE).paidAmount(0L).paymentStatus(Booking.PaymentStatus.UNPAID)
                .startsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2))
                .endsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(2).plusHours(2))
                .expiresAt(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1))
                .build();
    }

    private void providerCharges() {
        when(paymentProvider.charge(any())).thenReturn(
                new ProviderTransaction("txn-1", ProviderTransactionStatus.PAID));
    }

    // ---- the happy path ----

    @Test
    void aFullPaymentConfirmsTheBookingAndMarksItPaid() {
        providerCharges();
        Booking pending = booking("pending");

        service.chargeForBooking(pending, card, PRICE);

        assertThat(pending.getStatus()).isEqualTo("confirmed");
        assertThat(pending.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.PAID);
        assertThat(pending.getPaidAmount()).isEqualTo(PRICE);
        assertThat(pending.getExpiresAt()).isNull();
    }

    @Test
    void aMinimumPaymentConfirmsTheBookingButLeavesItPartiallyPaid() {
        providerCharges();
        Booking pending = booking("pending");

        service.chargeForBooking(pending, card, MINIMUM);

        assertThat(pending.getStatus()).isEqualTo("confirmed");
        assertThat(pending.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.PARTIALLY_PAID);
        assertThat(pending.getPaidAmount()).isEqualTo(MINIMUM);
        assertThat(pending.getRemainingAmount()).isEqualTo(PRICE - MINIMUM);
    }

    @Test
    void theOwnerPlatformSplitComesFromTheCommissionAtPaymentTime() {
        providerCharges();
        when(platformSettingsService.current())
                .thenReturn(PlatformSettings.builder().bookingCommissionPercent(10).build());

        Payment payment = service.chargeForBooking(booking("pending"), card, PRICE);

        assertThat(payment.getAmount()).isEqualTo(PRICE);
        assertThat(payment.getPlatformAmount()).isEqualTo(PRICE / 10);
        assertThat(payment.getOwnerAmount()).isEqualTo(PRICE - PRICE / 10);
        assertThat(payment.getCommissionPercent()).isEqualTo(10);
        assertThat(payment.getOwnerAmount() + payment.getPlatformAmount()).isEqualTo(payment.getAmount());
    }

    @Test
    void aPaymentRecordsItsMethodTypeAndProviderTransaction() {
        providerCharges();

        Payment payment = service.chargeForBooking(booking("pending"), card, PRICE);

        assertThat(payment.getMethod()).isEqualTo(Payment.Method.CARD);
        assertThat(payment.getPaymentType()).isEqualTo(Payment.PaymentType.BOOKING);
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(payment.getProviderTxnId()).isEqualTo("txn-1");
    }

    // ---- a failed charge ----

    @Test
    void aFailedChargeLeavesTheBookingPendingAndUnpaid() {
        when(paymentProvider.charge(any())).thenReturn(
                new ProviderTransaction("failed-1", ProviderTransactionStatus.FAILED));
        Booking pending = booking("pending");

        Payment payment = service.chargeForBooking(pending, card, PRICE);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.FAILED);
        assertThat(pending.getStatus()).isEqualTo("pending");
        assertThat(pending.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.UNPAID);
        assertThat(pending.getPaidAmount()).isZero();
        assertThat(pending.getExpiresAt()).isNotNull();
    }

    // ---- money that arrives after the hold expired ----

    @Test
    void paymentOnAnExpiredBookingConfirmsItWhenTheSlotIsStillFree() {
        providerCharges();
        when(bookingRepository.existsActiveOverlap(eq(STADIUM_ID), any(), any())).thenReturn(false);
        Booking expired = booking("expired");

        service.chargeForBooking(expired, card, PRICE);

        assertThat(expired.getStatus()).isEqualTo("confirmed");
        assertThat(expired.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.PAID);
        verify(paymentProvider, never()).refund(anyString());
    }

    @Test
    void paymentOnAnExpiredBookingWhoseSlotWasTakenIsRefundedInFull() {
        providerCharges();
        when(bookingRepository.existsActiveOverlap(eq(STADIUM_ID), any(), any())).thenReturn(true);
        when(paymentProvider.refund("txn-1")).thenReturn(
                new ProviderTransaction("txn-1", ProviderTransactionStatus.REFUNDED));
        Booking expired = booking("expired");

        service.chargeForBooking(expired, card, PRICE);

        verify(paymentProvider).refund("txn-1");
        assertThat(expired.getStatus()).isEqualTo("expired");
        assertThat(expired.getPaidAmount()).isZero();
        assertThat(expired.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.UNPAID);
    }

    @Test
    void theRefundForATakenSlotIsANewNegativeRowAndNotifiesThePlayer() {
        providerCharges();
        when(bookingRepository.existsActiveOverlap(eq(STADIUM_ID), any(), any())).thenReturn(true);
        when(paymentProvider.refund("txn-1")).thenReturn(
                new ProviderTransaction("txn-1", ProviderTransactionStatus.REFUNDED));

        service.chargeForBooking(booking("expired"), card, PRICE);

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, org.mockito.Mockito.atLeast(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .anySatisfy(p -> {
                    assertThat(p.getPaymentType()).isEqualTo(Payment.PaymentType.REFUND);
                    assertThat(p.getAmount()).isNegative();
                });

        verify(notificationService).notify(eq(USER_ID), eq(Notification.Type.BOOKING_CANCELLED),
                anyString(), anyString(), any(), eq(BOOKING_ID));
    }

    @Test
    void anOverlapOnReconfirmationIsRefundedRatherThanReachingTheProvider() {
        providerCharges();
        // The overlap check passes, but the database still rejects the UPDATE — the
        // last-moment race. It must be swallowed and refunded, never propagated.
        when(bookingRepository.existsActiveOverlap(eq(STADIUM_ID), any(), any())).thenReturn(false);
        when(bookingRepository.saveAndFlush(any(Booking.class)))
                .thenThrow(new DataIntegrityViolationException("bookings_no_overlap"));
        when(paymentProvider.refund("txn-1")).thenReturn(
                new ProviderTransaction("txn-1", ProviderTransactionStatus.REFUNDED));

        Payment payment = service.chargeForBooking(booking("expired"), card, PRICE);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
        verify(paymentProvider).refund("txn-1");
    }

    // ---- paying off the rest ----

    @Test
    void aRemainderPaymentOnAPlayedBookingLeavesItCompleted() {
        providerCharges();
        Booking played = booking("completed");
        played.setPaidAmount(MINIMUM);
        played.setPaymentStatus(Booking.PaymentStatus.PARTIALLY_PAID);

        service.chargeForBooking(played, card, PRICE - MINIMUM, Payment.PaymentType.REMAINDER);

        assertThat(played.getStatus()).isEqualTo("completed");
        assertThat(played.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.PAID);
        assertThat(played.getPaidAmount()).isEqualTo(PRICE);
    }

    @Test
    void aRemainderPaymentOnAConfirmedBookingCompletesThePrice() {
        providerCharges();
        Booking confirmed = booking("confirmed");
        confirmed.setPaidAmount(MINIMUM);
        confirmed.setPaymentStatus(Booking.PaymentStatus.PARTIALLY_PAID);

        service.chargeForBooking(confirmed, card, PRICE - MINIMUM, Payment.PaymentType.REMAINDER);

        assertThat(confirmed.getStatus()).isEqualTo("confirmed");
        assertThat(confirmed.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.PAID);
    }

    @Test
    void cashIsRefusedOnceTheBookingIsFullyPaid() {
        Booking paid = booking("confirmed");
        paid.setPaidAmount(PRICE);
        paid.setPaymentStatus(Booking.PaymentStatus.PAID);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(paid));

        assertThatThrownBy(() -> service.recordCashPayment(BOOKING_ID, OWNER_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Already paid");

        verify(paymentRepository, never()).save(any(Payment.class));
    }

    @Test
    void cashTakesOnlyWhatIsStillOwed() {
        Booking partly = booking("confirmed");
        partly.setPaidAmount(MINIMUM);
        partly.setPaymentStatus(Booking.PaymentStatus.PARTIALLY_PAID);
        when(bookingRepository.findById(BOOKING_ID)).thenReturn(Optional.of(partly));
        when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));

        Payment cash = service.recordCashPayment(BOOKING_ID, OWNER_ID);

        assertThat(cash.getAmount()).isEqualTo(PRICE - MINIMUM);
        assertThat(cash.getMethod()).isEqualTo(Payment.Method.CASH);
        assertThat(partly.getPaymentStatus()).isEqualTo(Booking.PaymentStatus.PAID);

        AuditEntry entry = Audited.one(auditService, AuditAction.CASH_RECEIVED);
        assertThat(entry.getActorType()).isEqualTo(AuditActorType.OWNER);
        assertThat(entry.getActorId()).isEqualTo(OWNER_ID);
        assertThat(entry.getEntityId()).isEqualTo(BOOKING_ID);
        assertThat(entry.getChanges()).containsExactly(
                AuditChange.of("paidAmount", MINIMUM, PRICE),
                AuditChange.of("paymentStatus", "partially_paid", "paid"));
    }
}
