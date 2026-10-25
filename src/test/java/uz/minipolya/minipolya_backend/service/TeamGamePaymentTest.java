package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant.Side;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.ChargeCommand;
import uz.minipolya.minipolya_backend.provider.ClickProvider;
import uz.minipolya.minipolya_backend.provider.PaymeProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.ProviderTransaction;
import uz.minipolya.minipolya_backend.provider.ProviderTransactionStatus;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * How a team's money reaches the provider. The team pays the platform fee plus
 * its owner advance in one movement, and the ATMOS split carries the two apart:
 * the advance is the owner's, the fee is the platform's.
 */
class TeamGamePaymentTest {

    private static final long FEE = 2_500_000L;       // 25 000 so'm
    private static final long ADVANCE = 2_500_000L;   // 25 000 so'm
    private static final UUID LEADER_ID = UUID.randomUUID();

    private PaymentRepository paymentRepository;
    private PaymentProvider paymentProvider;
    private PaymentService service;

    private User leader;
    private GameTeam gameTeam;
    private SavedCard card;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        paymentProvider = mock(PaymentProvider.class);
        PlatformSettingsService platformSettingsService = mock(PlatformSettingsService.class);
        when(platformSettingsService.current()).thenReturn(PlatformSettings.builder().build());

        service = new PaymentService(paymentRepository, mock(BookingRepository.class), mock(UserRepository.class),
                mock(GameRepository.class), mock(GameParticipantRepository.class), mock(PaymentMapper.class),
                List.of(new PaymeProvider(), new ClickProvider()), mock(GameService.class),
                mock(NotificationService.class), mock(AuditService.class),
                paymentProvider, platformSettingsService);

        leader = User.builder().id(LEADER_ID).name("Leader A").build();
        Stadium stadium = Stadium.builder().id(UUID.randomUUID()).name("Chilonzor Arena").build();
        Game game = Game.builder().id(UUID.randomUUID()).stadium(stadium).type("team_vs_team").build();
        gameTeam = GameTeam.builder()
                .id(UUID.randomUUID())
                .game(game)
                .team(Team.builder().id(UUID.randomUUID()).name("Team A").build())
                .leader(leader)
                .side(Side.A)
                .status(GameTeam.Status.ACTIVE)
                .feeAmount(FEE)
                .advanceAmount(ADVANCE)
                .remainderAmount(7_500_000L)
                .remainderStatus(GameTeam.RemainderStatus.UNPAID)
                .build();
        card = SavedCard.builder().id(UUID.randomUUID()).user(leader)
                .providerToken("tok-a").maskedNumber("8600 •••• 1234").isActive(true).build();

        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        when(paymentProvider.hold(any())).thenReturn(
                new ProviderTransaction("txn-hold-a", ProviderTransactionStatus.HELD));
    }

    @Test
    void theHoldSendsTheAdvanceToTheOwnerAndTheFeeToThePlatform() {
        service.holdForTeamGame(gameTeam, card);

        ArgumentCaptor<ChargeCommand> command = ArgumentCaptor.forClass(ChargeCommand.class);
        verifyHeld(command);
        assertThat(command.getValue().amount()).isEqualTo(FEE + ADVANCE);
        assertThat(command.getValue().ownerAmount()).isEqualTo(ADVANCE);
        assertThat(command.getValue().platformAmount()).isEqualTo(FEE);
        assertThat(command.getValue().cardToken()).isEqualTo("tok-a");
    }

    @Test
    void theHeldRowBelongsToTheGameTeam() {
        Payment payment = service.holdForTeamGame(gameTeam, card);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.HELD);
        assertThat(payment.getPaymentType()).isEqualTo(Payment.PaymentType.TEAM_GAME);
        assertThat(payment.getMethod()).isEqualTo(Payment.Method.CARD);
        assertThat(payment.getProvider()).isEqualTo(Payment.Provider.ATMOS);
        assertThat(payment.getProviderTxnId()).isEqualTo("txn-hold-a");
        assertThat(payment.getGameTeam()).isSameAs(gameTeam);
        assertThat(payment.getGame()).isSameAs(gameTeam.getGame());
        assertThat(payment.getUser()).isSameAs(leader);
        assertThat(payment.getBooking()).isNull();
    }

    @Test
    void noBookingCommissionIsTakenOnTopOfTheTeamFee() {
        Payment payment = service.holdForTeamGame(gameTeam, card);

        assertThat(payment.getCommissionPercent()).isZero();
        assertThat(payment.getOwnerAmount() + payment.getPlatformAmount()).isEqualTo(payment.getAmount());
    }

    @Test
    void aDeclinedCardProducesAFailedRowRatherThanAnException() {
        when(paymentProvider.hold(any())).thenReturn(
                new ProviderTransaction("failed-1", ProviderTransactionStatus.FAILED));

        Payment payment = service.holdForTeamGame(gameTeam, card);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.FAILED);
    }

    // ---- chargeForTeamGame ----

    @Test
    void theJoiningChargeSplitsTheSameWayAsTheHold() {
        when(paymentProvider.charge(any())).thenReturn(
                new ProviderTransaction("txn-charge-b", ProviderTransactionStatus.PAID));

        Payment payment = service.chargeForTeamGame(gameTeam, card, Payment.PaymentType.TEAM_GAME);

        ArgumentCaptor<ChargeCommand> command = ArgumentCaptor.forClass(ChargeCommand.class);
        org.mockito.Mockito.verify(paymentProvider).charge(command.capture());
        assertThat(command.getValue().amount()).isEqualTo(FEE + ADVANCE);
        assertThat(command.getValue().ownerAmount()).isEqualTo(ADVANCE);
        assertThat(command.getValue().platformAmount()).isEqualTo(FEE);
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PAID);
    }

    @Test
    void aRemainderChargeIsAllTheOwnersMoney() {
        when(paymentProvider.charge(any())).thenReturn(
                new ProviderTransaction("txn-remainder-b", ProviderTransactionStatus.PAID));

        Payment payment = service.chargeForTeamGame(gameTeam, card, Payment.PaymentType.REMAINDER);

        ArgumentCaptor<ChargeCommand> command = ArgumentCaptor.forClass(ChargeCommand.class);
        org.mockito.Mockito.verify(paymentProvider).charge(command.capture());
        assertThat(command.getValue().amount()).isEqualTo(7_500_000L);   // the remainder, no fee again
        assertThat(command.getValue().ownerAmount()).isEqualTo(7_500_000L);
        assertThat(command.getValue().platformAmount()).isZero();
        assertThat(payment.getPaymentType()).isEqualTo(Payment.PaymentType.REMAINDER);
    }

    // ---- captureHeld ----

    @Test
    void capturingAHoldTakesTheReservedMoney() {
        Payment held = heldPayment();
        when(paymentProvider.capture("txn-hold-a")).thenReturn(
                new ProviderTransaction("txn-hold-a", ProviderTransactionStatus.CAPTURED));

        service.captureHeld(held);

        assertThat(held.getStatus()).isEqualTo(Payment.Status.CAPTURED);
    }

    // ---- giveBackInFull ----

    @Test
    void givingBackAHoldReleasesItAndWritesNoRefundRow() {
        Payment held = heldPayment();
        when(paymentProvider.release("txn-hold-a")).thenReturn(
                new ProviderTransaction("txn-hold-a", ProviderTransactionStatus.RELEASED));

        Payment result = service.giveBackInFull(held, "Game cancelled");

        org.mockito.Mockito.verify(paymentProvider).release("txn-hold-a");
        org.mockito.Mockito.verify(paymentProvider, org.mockito.Mockito.never()).refund(any());
        assertThat(held.getStatus()).isEqualTo(Payment.Status.RELEASED);
        assertThat(result).isNull();   // no money moved, so there is nothing to record
    }

    @Test
    void givingBackACapturedPaymentRefundsItInFull() {
        Payment captured = heldPayment();
        captured.setStatus(Payment.Status.CAPTURED);
        when(paymentProvider.refund("txn-hold-a")).thenReturn(
                new ProviderTransaction("txn-hold-a", ProviderTransactionStatus.REFUNDED));

        Payment refund = service.giveBackInFull(captured, "Game cancelled");

        assertThat(refund).isNotNull();
        assertThat(refund.getAmount()).isEqualTo(-(FEE + ADVANCE));
        assertThat(captured.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void givingBackAFailedPaymentDoesNothing() {
        Payment failed = heldPayment();
        failed.setStatus(Payment.Status.FAILED);

        assertThat(service.giveBackInFull(failed, "Game cancelled")).isNull();
        org.mockito.Mockito.verifyNoInteractions(paymentProvider);
    }

    // ---- giveBackAllTeamPayments ----

    @Test
    void aTeamGetsBackItsHoldAndItsSettledPaymentsTogether() {
        Payment held = heldPayment();
        Payment remainder = heldPayment();
        remainder.setStatus(Payment.Status.PAID);
        remainder.setProviderTxnId("txn-remainder-a");
        remainder.setAmount(7_500_000L);
        remainder.setOwnerAmount(7_500_000L);
        remainder.setPlatformAmount(0L);
        when(paymentRepository.findByGameTeamId(gameTeam.getId())).thenReturn(List.of(held, remainder));
        when(paymentProvider.release("txn-hold-a")).thenReturn(
                new ProviderTransaction("txn-hold-a", ProviderTransactionStatus.RELEASED));
        when(paymentProvider.refund("txn-remainder-a")).thenReturn(
                new ProviderTransaction("txn-remainder-a", ProviderTransactionStatus.REFUNDED));

        long returned = service.giveBackAllTeamPayments(gameTeam.getId(), "Game cancelled");

        assertThat(returned).isEqualTo(FEE + ADVANCE + 7_500_000L);
    }

    private Payment heldPayment() {
        return Payment.builder()
                .id(UUID.randomUUID())
                .game(gameTeam.getGame())
                .gameTeam(gameTeam)
                .user(leader)
                .amount(FEE + ADVANCE)
                .ownerAmount(ADVANCE)
                .platformAmount(FEE)
                .commissionPercent(0)
                .method(Payment.Method.CARD)
                .paymentType(Payment.PaymentType.TEAM_GAME)
                .provider(Payment.Provider.ATMOS)
                .providerTxnId("txn-hold-a")
                .status(Payment.Status.HELD)
                .build();
    }

    private void verifyHeld(ArgumentCaptor<ChargeCommand> command) {
        org.mockito.Mockito.verify(paymentProvider).hold(command.capture());
    }
}
