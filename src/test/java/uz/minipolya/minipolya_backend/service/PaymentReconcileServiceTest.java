package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.ChargeOutcome;
import uz.minipolya.minipolya_backend.provider.ClickProvider;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.PaymeProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;
import uz.minipolya.minipolya_backend.provider.TransactionClosedException;
import uz.minipolya.minipolya_backend.repository.OwnerLegalDetailsRepository;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.scheduler.PaymentReconcileJob;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** PaymentReconcileService over a real PaymentService and a stubbed provider. */
class PaymentReconcileServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-29T12:00:00Z");

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final BookingRepository bookingRepository = mock(BookingRepository.class);
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final TeamGameService teamGameService = mock(TeamGameService.class);
    private final GameService gameService = mock(GameService.class);
    private PaymentReconcileService service;

    private User player;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.current()).thenReturn(PlatformSettings.builder().build());
        PaymentService paymentService = new PaymentService(paymentRepository, bookingRepository,
                mock(UserRepository.class), mock(GameRepository.class), mock(GameParticipantRepository.class),
                mock(PaymentMapper.class), List.of(new PaymeProvider(), new ClickProvider()), gameService,
                mock(NotificationService.class), mock(AuditService.class), provider, settings, mock(OwnerLegalDetailsRepository.class));
        service = new PaymentReconcileService(paymentRepository, provider, paymentService, teamGameService,
                gameService, Clock.fixed(NOW, ZoneOffset.UTC));

        player = User.builder().id(UUID.randomUUID()).name("Player").build();
        stadium = Stadium.builder().id(UUID.randomUUID()).name("Arena")
                .owner(User.builder().id(UUID.randomUUID()).name("Owner").build()).build();
        when(paymentRepository.save(any(Payment.class))).thenAnswer(ProviderStubs.savedWithId());
        when(bookingRepository.saveAndFlush(any(Booking.class))).thenAnswer(inv -> inv.getArgument(0));
        ProviderStubs.behaveLikeAtmos(provider);
    }

    private Payment row(Payment.Status status, String providerTxnId, int minutesOld) {
        Payment payment = Payment.builder().id(UUID.randomUUID()).user(player)
                .amount(20_310_000L).ownerAmount(20_304_569L).platformAmount(5_431L).cardFeeExpected(310_000L)
                .commissionPercent(0).method(Payment.Method.CARD).provider(Payment.Provider.ATMOS)
                .paymentType(Payment.PaymentType.BOOKING).providerTxnId(providerTxnId).status(status)
                .createdAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(minutesOld))
                .build();
        when(paymentRepository.findById(payment.getId())).thenReturn(Optional.of(payment));
        return payment;
    }

    private Booking pendingBooking() {
        OffsetDateTime start = OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(2);
        return Booking.builder().id(UUID.randomUUID()).stadium(stadium).user(player).status("pending")
                .price(20_000_000L).paidAmount(0L).paymentStatus(Booking.PaymentStatus.UNPAID)
                .startsAt(start).endsAt(start.plusHours(1)).build();
    }

    private GameTeam team(GameTeam.Status status, String gameStatus) {
        Game game = Game.builder().id(UUID.randomUUID()).type("team_vs_team").status(gameStatus).stadium(stadium).build();
        return GameTeam.builder().id(UUID.randomUUID()).game(game).side(Side.B).status(status)
                .team(Team.builder().id(UUID.randomUUID()).name("B").build()).leader(player)
                .feeAmount(2_500_000L).advanceAmount(2_500_000L).remainderAmount(7_500_000L)
                .remainderStatus(GameTeam.RemainderStatus.UNPAID).build();
    }

    // ---- what is due ----

    @Test
    void onlyRowsStuckForFiveMinutesInAPendingStepAreDue() {
        service.due();

        ArgumentCaptor<OffsetDateTime> cutoff = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(paymentRepository).findByStatusInAndUpdatedAtBefore(eq(PaymentReconcileService.STUCK), cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).minusMinutes(5));
        assertThat(PaymentReconcileService.STUCK).containsExactlyInAnyOrder(
                Payment.Status.PROCESSING, Payment.Status.RETURN_PENDING, Payment.Status.CAPTURE_PENDING);
    }

    // ---- processing charges ----

    @Test
    void aLostAnswerThatWasPaidConfirmsTheBooking() {
        Payment payment = row(Payment.Status.PROCESSING, "txn-1", 10);
        Booking booking = pendingBooking();
        payment.setBooking(booking);
        when(provider.getCharge("txn-1")).thenReturn(
                new ChargeOutcome(ProviderStatus.SUCCESS, List.of("succ-1"), 304_646L, "https://ofd/1", null));

        service.reconcile(payment.getId());

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(payment.getProviderSuccessTxnIds()).isEqualTo("succ-1");
        assertThat(payment.getProviderCommission()).isEqualTo(304_646L);
        assertThat(payment.getOfdUrl()).isEqualTo("https://ofd/1");
        assertThat(booking.getStatus()).isEqualTo("confirmed");
        assertThat(booking.getPaidAmount()).isEqualTo(20_000_000L);   // the net, not the gross
    }

    @Test
    void aLostAnswerThatFailedIsMarkedFailed() {
        Payment payment = row(Payment.Status.PROCESSING, "txn-1", 10);
        payment.setBooking(pendingBooking());
        when(provider.getCharge("txn-1")).thenReturn(ChargeOutcome.failed("STPIMS-ERR-057"));

        service.reconcile(payment.getId());

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.FAILED);
    }

    @Test
    void aChargeTheProviderNeverAcknowledgedMovedNoMoney() {
        Payment payment = row(Payment.Status.PROCESSING, null, 10);

        service.reconcile(payment.getId());

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.FAILED);
        verify(provider, never()).getCharge(anyString());
    }

    @Test
    void anUnconfirmedChargeWaitsAWhileThenCountsAsAbandoned() {
        Payment young = row(Payment.Status.PROCESSING, "txn-young", 10);
        Payment old = row(Payment.Status.PROCESSING, "txn-old", 45);
        when(provider.getCharge(anyString())).thenReturn(ChargeOutcome.pending());

        service.reconcile(young.getId());
        service.reconcile(old.getId());

        assertThat(young.getStatus()).isEqualTo(Payment.Status.PROCESSING);
        assertThat(old.getStatus()).isEqualTo(Payment.Status.FAILED);
    }

    @Test
    void aJoinChargeThatWentThroughAfterTheJoinWasAbandonedIsGivenBack() {
        Payment payment = row(Payment.Status.PROCESSING, "txn-join", 10);
        payment.setPaymentType(Payment.PaymentType.TEAM_GAME);
        payment.setGameTeam(team(GameTeam.Status.CANCELLED, "open"));
        when(provider.getCharge("txn-join")).thenReturn(
                new ChargeOutcome(ProviderStatus.SUCCESS, List.of("succ-join"), null, null, null));

        service.reconcile(payment.getId());

        verify(provider).reverse(eq("succ-join"), anyString());
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void aRemainderThatWentThroughSettlesTheTeam() {
        Payment payment = row(Payment.Status.PROCESSING, "txn-rem", 10);
        payment.setPaymentType(Payment.PaymentType.REMAINDER);
        GameTeam team = team(GameTeam.Status.ACTIVE, "confirmed");
        payment.setGameTeam(team);
        when(provider.getCharge("txn-rem")).thenReturn(
                new ChargeOutcome(ProviderStatus.SUCCESS, List.of("succ-rem"), null, null, null));

        service.reconcile(payment.getId());

        verify(teamGameService).settleRemainderPaidLate(team);
        verify(provider, never()).reverse(anyString(), any());
    }

    // ---- processing holds ----

    @Test
    void aCreatorsHoldThatNeverWentThroughEndsTheWaitingGame() {
        Payment payment = row(Payment.Status.PROCESSING, "hold-1", 10);
        payment.setHoldExpiresAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(2));
        GameTeam team = team(GameTeam.Status.ACTIVE, "open");
        payment.setGameTeam(team);
        when(provider.getHold("hold-1")).thenReturn(new HoldOutcome("hold-1", ProviderStatus.FAILED, null, null, "X"));

        service.reconcile(payment.getId());

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.FAILED);
        verify(teamGameService).endWaitingGame(eq(team.getGame()), eq("cancelled_not_filled"), anyString());
    }

    @Test
    void aCreatorsHoldThatDidGoThroughStaysHeld() {
        Payment payment = row(Payment.Status.PROCESSING, "hold-1", 10);
        payment.setHoldExpiresAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(2));
        payment.setGameTeam(team(GameTeam.Status.ACTIVE, "open"));
        when(provider.getHold("hold-1")).thenReturn(new HoldOutcome("hold-1", ProviderStatus.HELD, null, null, null));

        service.reconcile(payment.getId());

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.HELD);
        verify(provider, never()).releaseHold(anyString());
    }

    // ---- return_pending / capture_pending ----

    @Test
    void anUnfinishedRefundIsReversedAndRecorded() {
        Payment payment = row(Payment.Status.RETURN_PENDING, "txn-1", 10);
        payment.setProviderSuccessTxnIds("succ-1");

        service.reconcile(payment.getId());

        verify(provider).reverse(eq("succ-1"), anyString());
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        assertThat(saved.getAllValues()).anySatisfy(p -> {
            assertThat(p.getPaymentType()).isEqualTo(Payment.PaymentType.REFUND);
            assertThat(p.getAmount()).isEqualTo(-20_310_000L);   // the whole gross, fee included
        });
    }

    @Test
    void anUnfinishedRefundAlreadyReversedAtTheProviderIsStillFinished() {
        Payment payment = row(Payment.Status.RETURN_PENDING, "txn-1", 10);
        payment.setProviderSuccessTxnIds("succ-1");
        doThrow(new TransactionClosedException("STPIMS-ERR-092")).when(provider).reverse(eq("succ-1"), anyString());

        service.reconcile(payment.getId());

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void anUnfinishedReleaseOfAHeldHoldIsReleased() {
        Payment payment = row(Payment.Status.RETURN_PENDING, "hold-1", 10);
        payment.setHoldExpiresAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1));
        when(provider.getHold("hold-1")).thenReturn(new HoldOutcome("hold-1", ProviderStatus.HELD, null, null, null));

        service.reconcile(payment.getId());

        verify(provider).releaseHold("hold-1");
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.RELEASED);
    }

    @Test
    void aGiveBackOfAHoldThatWasCapturedAfterAllIsReversed() {
        Payment payment = row(Payment.Status.RETURN_PENDING, "hold-1", 10);
        payment.setHoldExpiresAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1));
        when(provider.getHold("hold-1")).thenReturn(new HoldOutcome("hold-1", ProviderStatus.CAPTURED, null, null, null));

        service.reconcile(payment.getId());

        verify(provider).reverse(eq("hold-1"), anyString());
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void anUnfinishedCaptureIsCaptured() {
        Payment payment = row(Payment.Status.CAPTURE_PENDING, "hold-1", 10);
        payment.setHoldExpiresAt(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(1));
        when(provider.getHold("hold-1")).thenReturn(new HoldOutcome("hold-1", ProviderStatus.HELD, null, null, null));

        service.reconcile(payment.getId());

        verify(provider).captureHold("hold-1");
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.CAPTURED);
    }

    // ---- idempotence ----

    @Test
    void aRowSettledMeanwhileIsLeftAlone() {
        Payment payment = row(Payment.Status.PAID, "txn-1", 10);

        service.reconcile(payment.getId());

        verifyNoInteractions(provider);
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PAID);
    }

    @Test
    void theJobCarriesOnPastARowThatFails() {
        PaymentReconcileService reconciler = mock(PaymentReconcileService.class);
        UUID bad = UUID.randomUUID();
        UUID good = UUID.randomUUID();
        when(reconciler.due()).thenReturn(List.of(bad, good));
        doThrow(new IllegalStateException("provider down")).when(reconciler).reconcile(bad);

        new PaymentReconcileJob(reconciler).reconcileStuckPayments();

        verify(reconciler).reconcile(good);
    }
}
