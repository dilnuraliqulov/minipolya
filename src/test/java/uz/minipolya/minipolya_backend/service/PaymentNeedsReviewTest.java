package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.Audited;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.OwnerLegalDetailsRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F6: giving back a hold whose state ATMOS reports unclearly. "Not held any more" is a plain
 * release only when no capture was ever asked for; once one was, it may mean the money was
 * taken, so the row is left needs_review for an admin instead of being recorded released.
 */
class PaymentNeedsReviewTest {

    private PaymentRepository paymentRepository;
    private PaymentProvider provider;
    private AuditService auditService;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        provider = mock(PaymentProvider.class);
        auditService = mock(AuditService.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.current()).thenReturn(PlatformSettings.builder().build());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new PaymentService(paymentRepository, mock(BookingRepository.class), mock(UserRepository.class),
                mock(GameRepository.class), mock(GameParticipantRepository.class), mock(PaymentMapper.class),
                mock(GameService.class), mock(NotificationService.class), auditService,
                provider, settings, mock(OwnerLegalDetailsRepository.class));
    }

    private Payment returning(OffsetDateTime captureRequestedAt) {
        Payment row = Payment.builder().id(UUID.randomUUID()).amount(5_500_000L).ownerAmount(2_961_928L)
                .platformAmount(2_538_072L).cardFeeExpected(82_500L).commissionPercent(0)
                .method(Payment.Method.CARD).paymentType(Payment.PaymentType.TEAM_GAME)
                .provider(Payment.Provider.ATMOS).providerTxnId("4001")
                .holdExpiresAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(1))
                .captureRequestedAt(captureRequestedAt)
                .status(Payment.Status.RETURN_PENDING).build();
        when(paymentRepository.findForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(paymentRepository.lockStatus(row.getId())).thenReturn("return_pending");
        return row;
    }

    private void atmosSays(ProviderStatus status) {
        when(provider.getHold("4001")).thenReturn(new HoldOutcome("4001", status, null, null, null));
    }

    @Test
    void aHoldNeverAskedToBeCapturedAndNowGoneIsReleased() {
        Payment row = returning(null);
        atmosSays(ProviderStatus.RELEASED);

        service.completeReturn(row.getId(), "Team cancelled in time");

        assertThat(row.getStatus()).isEqualTo(Payment.Status.RELEASED);
    }

    @Test
    void aHoldAskedToBeCapturedAndNowGoneNeedsReviewAndIsNeverRecordedReleased() {
        Payment row = returning(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        atmosSays(ProviderStatus.RELEASED);

        service.completeReturn(row.getId(), "Team cancelled in time");

        assertThat(row.getStatus()).isEqualTo(Payment.Status.NEEDS_REVIEW);
        assertThat(row.getFailureReason()).startsWith("Give-back unclear");
        verify(provider, never()).releaseHold(anyString());
        verify(provider, never()).reverse(anyString(), any());
        assertThat(Audited.one(auditService, AuditAction.PAYMENT_NEEDS_REVIEW)).isNotNull();
    }

    @Test
    void aHoldReadBackAsCapturedIsReversed() {
        Payment row = returning(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        atmosSays(ProviderStatus.CAPTURED);

        service.completeReturn(row.getId(), "Team cancelled in time");

        verify(provider).reverse("4001", "Team cancelled in time");
        assertThat(row.getStatus()).isEqualTo(Payment.Status.REFUNDED);
    }

    @Test
    void aHoldStillHeldIsReleasedWhateverWasAskedBefore() {
        Payment row = returning(OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10));
        atmosSays(ProviderStatus.HELD);

        service.completeReturn(row.getId(), "Team cancelled in time");

        verify(provider).releaseHold("4001");
        assertThat(row.getStatus()).isEqualTo(Payment.Status.RELEASED);
    }
}
