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
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.ClickProvider;
import uz.minipolya.minipolya_backend.provider.HoldCancelledException;
import uz.minipolya.minipolya_backend.provider.HoldExpiredException;
import uz.minipolya.minipolya_backend.provider.HoldRequest;
import uz.minipolya.minipolya_backend.provider.PaymeProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.ProviderTimeoutException;
import uz.minipolya.minipolya_backend.provider.Split;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.PriceCalculator;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How a team's money reaches the provider. The team pays the platform fee plus its owner
 * advance in one movement, grossed up by the card fee, and the ATMOS split carries the two
 * apart: the advance to the owner's recipient, the fee to the platform.
 */
class TeamGamePaymentTest {

    private static final long FEE = 2_500_000L;       // 25 000 so'm
    private static final long ADVANCE = 2_500_000L;   // 25 000 so'm
    private static final UUID LEADER_ID = UUID.randomUUID();
    private static final int MINUTES = 600;

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
        User owner = User.builder().id(UUID.randomUUID()).name("Owner").atmosRecipientId("rcp-owner").build();
        Stadium stadium = Stadium.builder().id(UUID.randomUUID()).name("Chilonzor Arena").owner(owner).build();
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

        when(paymentRepository.save(any(Payment.class))).thenAnswer(ProviderStubs.savedWithId());
        ProviderStubs.behaveLikeAtmos(paymentProvider);
    }

    @Test
    void theHoldSendsTheAdvanceToTheOwnerAndTheFeeToThePlatform() {
        Payment payment = service.holdForTeamGame(gameTeam, card, MINUTES);

        ArgumentCaptor<HoldRequest> request = ArgumentCaptor.forClass(HoldRequest.class);
        verify(paymentProvider).hold(request.capture());
        HoldRequest hold = request.getValue();
        // fee + advance, grossed up: the owner's split nets the advance after the card fee,
        // the platform's split the fee (plus the rounding remainder).
        assertThat(hold.total()).isEqualTo(TestPrices.card(FEE + ADVANCE));
        assertThat(hold.splits()).containsExactly(
                Split.owner("rcp-owner", PriceCalculator.grossUp(ADVANCE, 150)),
                Split.platform(TestPrices.card(FEE + ADVANCE) - PriceCalculator.grossUp(ADVANCE, 150)));
        assertThat(hold.cardToken()).isEqualTo("tok-a");
        assertThat(hold.durationMinutes()).isEqualTo(MINUTES);
        // Our payment id is the provider's account, so a retry can never hold twice.
        assertThat(hold.account()).isEqualTo(payment.getId().toString());
    }

    @Test
    void theHeldRowBelongsToTheGameTeam() {
        Payment payment = service.holdForTeamGame(gameTeam, card, MINUTES);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.HELD);
        assertThat(payment.getPaymentType()).isEqualTo(Payment.PaymentType.TEAM_GAME);
        assertThat(payment.getMethod()).isEqualTo(Payment.Method.CARD);
        assertThat(payment.getProvider()).isEqualTo(Payment.Provider.ATMOS);
        assertThat(payment.getProviderTxnId()).isEqualTo("hold-" + payment.getId());
        assertThat(payment.getHoldExpiresAt()).isNotNull();
        assertThat(payment.getGameTeam()).isSameAs(gameTeam);
        assertThat(payment.getGame()).isSameAs(gameTeam.getGame());
        assertThat(payment.getUser()).isSameAs(leader);
        assertThat(payment.getBooking()).isNull();
    }

    @Test
    void noBookingCommissionIsTakenOnTopOfTheTeamFee() {
        Payment payment = service.holdForTeamGame(gameTeam, card, MINUTES);

        assertThat(payment.getCommissionPercent()).isZero();
        assertThat(payment.getOwnerAmount() + payment.getPlatformAmount()).isEqualTo(payment.getAmount());
    }

    @Test
    void aDeclinedCardProducesAFailedRowRatherThanAnException() {
        ProviderStubs.declineHolds(paymentProvider);

        Payment payment = service.holdForTeamGame(gameTeam, card, MINUTES);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.FAILED);
    }

    @Test
    void aHoldWhoseAnswerIsLostStaysProcessing() {
        when(paymentProvider.confirmHold(anyString(), any()))
                .thenThrow(new ProviderTimeoutException("hold apply", null));

        Payment payment = service.holdForTeamGame(gameTeam, card, MINUTES);

        // Never assumed failed: the reconcile job asks the provider.
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PROCESSING);
        assertThat(payment.getProviderTxnId()).isEqualTo("hold-" + payment.getId());
    }

    // ---- chargeForTeamGame ----

    @Test
    void theJoiningChargeSplitsTheSameWayAsTheHold() {
        Payment payment = service.chargeForTeamGame(gameTeam, card, Payment.PaymentType.TEAM_GAME);

        ArgumentCaptor<ChargeRequest> request = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(paymentProvider).charge(request.capture());
        assertThat(request.getValue().total()).isEqualTo(TestPrices.card(FEE + ADVANCE));
        assertThat(request.getValue().splits().getFirst())
                .isEqualTo(Split.owner("rcp-owner", PriceCalculator.grossUp(ADVANCE, 150)));
        assertThat(request.getValue().account()).isEqualTo(payment.getId().toString());
        assertThat(payment.getCardFeeExpected()).isEqualTo(TestPrices.card(FEE + ADVANCE) - FEE - ADVANCE);
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(payment.getProviderSuccessTxnIds()).isEqualTo("succ-txn-" + payment.getId());
    }

    @Test
    void aRemainderChargeIsAllTheOwnersMoney() {
        Payment payment = service.chargeForTeamGame(gameTeam, card, Payment.PaymentType.REMAINDER);

        ArgumentCaptor<ChargeRequest> request = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(paymentProvider).charge(request.capture());
        // The remainder grossed up, all of it the owner's: no platform fee again — the
        // platform's split is only what the rounding step added.
        assertThat(request.getValue().total()).isEqualTo(TestPrices.card(7_500_000L));
        assertThat(request.getValue().splits().getFirst())
                .isEqualTo(Split.owner("rcp-owner", PriceCalculator.grossUp(7_500_000L, 150)));
        assertThat(payment.getPlatformAmount()).isLessThan(10_000L);
        assertThat(payment.getPaymentType()).isEqualTo(Payment.PaymentType.REMAINDER);
    }

    // ---- captureHeld ----

    @Test
    void capturingAHoldTakesTheReservedMoney() {
        Payment held = heldPayment();

        service.captureHeld(held);

        verify(paymentProvider).captureHold("hold-a");
        assertThat(held.getStatus()).isEqualTo(Payment.Status.CAPTURED);
    }

    @Test
    void aLapsedHoldCannotBeCaptured() {
        Payment held = heldPayment();
        when(paymentProvider.captureHold("hold-a")).thenThrow(new HoldExpiredException("HOLD_EXPIRED"));

        assertThatThrownBy(() -> service.captureHeld(held)).isInstanceOf(HoldExpiredException.class);
        assertThat(held.getStatus()).isEqualTo(Payment.Status.HELD);
    }

    // ---- giveBackInFull ----

    @Test
    void givingBackAHoldReleasesItAndWritesNoRefundRow() {
        Payment held = heldPayment();

        Payment result = service.giveBackInFull(held, "Game cancelled");

        verify(paymentProvider).releaseHold("hold-a");
        verify(paymentProvider, never()).reverse(anyString(), any());
        assertThat(held.getStatus()).isEqualTo(Payment.Status.RELEASED);
        assertThat(result).isNull();   // no money moved, so there is nothing to record
    }

    @Test
    void givingBackACapturedPaymentReversesItInFull() {
        Payment captured = heldPayment();
        captured.setStatus(Payment.Status.CAPTURED);

        Payment refund = service.giveBackInFull(captured, "Game cancelled");

        verify(paymentProvider).reverse("hold-a", "Game cancelled");
        assertThat(refund).isNotNull();
        assertThat(refund.getAmount()).isEqualTo(-(FEE + ADVANCE));
        assertThat(captured.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void aRefundRowStaysAttachedToTheTeamThatPaid() {
        Payment captured = heldPayment();
        captured.setStatus(Payment.Status.CAPTURED);

        Payment refund = service.giveBackInFull(captured, "Game cancelled");

        // A team game's money hangs off (game, team); a refund row with no
        // team would silently drop out of that team's ledger.
        assertThat(refund.getGameTeam()).isSameAs(gameTeam);
        assertThat(refund.getGame()).isSameAs(gameTeam.getGame());
    }

    @Test
    void releasingAHoldThatIsAlreadyGoneCountsAsReleased() {
        Payment held = heldPayment();
        when(paymentProvider.releaseHold("hold-a")).thenThrow(new HoldCancelledException("STPIMS-ERR-145"));

        // Released before (a retry, the timeout job running twice) or lapsed at the
        // provider: either way nothing is reserved any more.
        Payment result = service.giveBackInFull(held, "Game cancelled");

        assertThat(result).isNull();
        assertThat(held.getStatus()).isEqualTo(Payment.Status.RELEASED);
    }

    @Test
    void anyOtherRefusalToReleaseIsNotSwallowed() {
        Payment held = heldPayment();
        when(paymentProvider.releaseHold("hold-a")).thenThrow(new PaymentProviderException("STPIMS-ERR-143", "no"));

        assertThatThrownBy(() -> service.giveBackInFull(held, "Game cancelled"))
                .isInstanceOf(PaymentProviderException.class);
        assertThat(held.getStatus()).isEqualTo(Payment.Status.HELD);
    }

    @Test
    void givingBackAFailedPaymentDoesNothing() {
        Payment failed = heldPayment();
        failed.setStatus(Payment.Status.FAILED);

        assertThat(service.giveBackInFull(failed, "Game cancelled")).isNull();
        verifyNoInteractions(paymentProvider);
    }

    // ---- giveBackAllTeamPayments ----

    @Test
    void aTeamGetsBackItsHoldAndItsSettledPaymentsTogether() {
        Payment held = heldPayment();
        Payment remainder = heldPayment();
        remainder.setStatus(Payment.Status.PAID);
        remainder.setProviderTxnId("txn-remainder-a");
        remainder.setProviderSuccessTxnIds("succ-remainder-a");
        remainder.setAmount(7_500_000L);
        remainder.setOwnerAmount(7_500_000L);
        remainder.setPlatformAmount(0L);
        when(paymentRepository.findByGameTeamId(gameTeam.getId())).thenReturn(List.of(held, remainder));

        long returned = service.giveBackAllTeamPayments(gameTeam.getId(), "Game cancelled");

        verify(paymentProvider).releaseHold("hold-a");
        verify(paymentProvider).reverse("succ-remainder-a", "Game cancelled");
        assertThat(returned).isEqualTo(FEE + ADVANCE + 7_500_000L);
    }

    // ---- keepAllTeamPayments ----

    @Test
    void keepingATeamsMoneyCapturesItsHoldAndLeavesSettledPaymentsAlone() {
        Payment held = heldPayment();
        Payment settled = heldPayment();
        settled.setStatus(Payment.Status.PAID);
        settled.setProviderTxnId("txn-remainder-a");
        when(paymentRepository.findByGameTeamId(gameTeam.getId())).thenReturn(List.of(held, settled));

        long kept = service.keepAllTeamPayments(gameTeam.getId());

        assertThat(held.getStatus()).isEqualTo(Payment.Status.CAPTURED);
        assertThat(settled.getStatus()).isEqualTo(Payment.Status.PAID);
        verify(paymentProvider, never()).reverse(anyString(), any());
        assertThat(kept).isEqualTo(2 * (FEE + ADVANCE));
    }

    // ---- refundableTeamTotal ----

    @Test
    void theRefundablePreviewCountsHoldsAndSettledPaymentsAlike() {
        Payment held = heldPayment();
        Payment settled = heldPayment();
        settled.setStatus(Payment.Status.CAPTURED);
        Payment failed = heldPayment();
        failed.setStatus(Payment.Status.FAILED);
        when(paymentRepository.findByGameTeamId(gameTeam.getId())).thenReturn(List.of(held, settled, failed));

        assertThat(service.refundableTeamTotal(gameTeam.getId())).isEqualTo(2 * (FEE + ADVANCE));
    }

    @Test
    void theRefundablePreviewIsZeroOnceTheMoneyHasGoneBack() {
        Payment refunded = heldPayment();
        refunded.setStatus(Payment.Status.REFUNDED);
        Payment released = heldPayment();
        released.setStatus(Payment.Status.RELEASED);
        when(paymentRepository.findByGameTeamId(gameTeam.getId())).thenReturn(List.of(refunded, released));

        assertThat(service.refundableTeamTotal(gameTeam.getId())).isZero();
    }

    @Test
    void thePreviewEqualsWhatGivingTheMoneyBackActuallyReturns() {
        Payment held = heldPayment();
        when(paymentRepository.findByGameTeamId(gameTeam.getId())).thenReturn(List.of(held));

        long preview = service.refundableTeamTotal(gameTeam.getId());
        long actual = service.giveBackAllTeamPayments(gameTeam.getId(), "Game cancelled");

        assertThat(actual).isEqualTo(preview);
    }

    @Test
    void reversingATransactionAlreadyReversedIsDoneNotAnError() {
        Payment settled = heldPayment();
        settled.setStatus(Payment.Status.PAID);
        settled.setProviderSuccessTxnIds("succ-1,succ-2");
        doThrow(new uz.minipolya.minipolya_backend.provider.TransactionClosedException("STPIMS-ERR-092"))
                .when(paymentProvider).reverse("succ-1", "Game cancelled");

        service.giveBackInFull(settled, "Game cancelled");

        // Both splits are reversed; the one closed before is simply done.
        verify(paymentProvider).reverse("succ-2", "Game cancelled");
        assertThat(settled.getStatus()).isEqualTo(Payment.Status.REFUNDED);
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
                .providerTxnId("hold-a")
                .status(Payment.Status.HELD)
                .build();
    }
}
