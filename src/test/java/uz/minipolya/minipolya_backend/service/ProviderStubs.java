package uz.minipolya.minipolya_backend.service;

import org.mockito.stubbing.Answer;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.provider.ChargeOutcome;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.HoldOutcome;
import uz.minipolya.minipolya_backend.provider.HoldRequest;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.PendingCharge;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;
import uz.minipolya.minipolya_backend.repository.OwnerLegalDetailsRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;

/**
 * A mocked {@link PaymentProvider} that answers like ATMOS for a saved card: a charge is
 * created ({@code txn-<account>}) and confirmed ({@code succ-txn-<account>}); a hold is
 * {@code hold-<account>}, held on confirm, captured or released on request. Tests override
 * single calls to make one step fail.
 */
final class ProviderStubs {

    private ProviderStubs() {
    }

    /** Owner legal details whose every owner has a signed ATMOS contract paying to {@code recipientId}. */
    static OwnerLegalDetailsRepository ownersPaidTo(String recipientId) {
        OwnerLegalDetailsRepository repository = org.mockito.Mockito.mock(OwnerLegalDetailsRepository.class);
        lenient().when(repository.findById(any())).thenReturn(java.util.Optional.of(OwnerLegalDetails.builder()
                .atmosContractStatus(OwnerLegalDetails.ContractStatus.SIGNED)
                .atmosRecipientId(recipientId)
                .build()));
        return repository;
    }

    static void behaveLikeAtmos(PaymentProvider provider) {
        lenient().when(provider.charge(any())).thenAnswer(inv -> {
            ChargeRequest request = inv.getArgument(0);
            return new PendingCharge(List.of("txn-" + request.account()), false);
        });
        lenient().when(provider.confirmCharge(any(), any())).thenAnswer(inv -> {
            List<String> ids = inv.getArgument(0);
            return new ChargeOutcome(ProviderStatus.SUCCESS, ids.stream().map(id -> "succ-" + id).toList(),
                    null, null, null);
        });
        lenient().when(provider.hold(any())).thenAnswer(inv -> {
            HoldRequest request = inv.getArgument(0);
            return new HoldOutcome("hold-" + request.account(), ProviderStatus.PENDING,
                    OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(request.durationMinutes()), null, null);
        });
        lenient().when(provider.confirmHold(anyString(), any())).thenAnswer(inv ->
                new HoldOutcome(inv.getArgument(0), ProviderStatus.HELD, null, null, null));
        lenient().when(provider.captureHold(anyString())).thenAnswer(inv ->
                new HoldOutcome(inv.getArgument(0), ProviderStatus.CAPTURED, null, null, null));
        lenient().when(provider.releaseHold(anyString())).thenAnswer(inv ->
                new HoldOutcome(inv.getArgument(0), ProviderStatus.RELEASED, null, null, null));
    }

    /** Every charge confirmation comes back declined. */
    static void declineCharges(PaymentProvider provider) {
        lenient().doReturn(ChargeOutcome.failed("DECLINED")).when(provider).confirmCharge(any(), any());
    }

    /** Every hold confirmation comes back declined. */
    static void declineHolds(PaymentProvider provider) {
        lenient().doAnswer(inv -> new HoldOutcome(inv.getArgument(0), ProviderStatus.FAILED, null, null, "DECLINED"))
                .when(provider).confirmHold(anyString(), any());
    }

    /** {@code paymentRepository.save} as the database does it: a new row gets its id. */
    static Answer<Payment> savedWithId() {
        return inv -> {
            Payment row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            return row;
        };
    }
}
