package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ProviderUnavailableException;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.AtmosCodes;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.Split;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameParticipantRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.OwnerLegalDetailsRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.CardFeeSplit;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * F2 / F7: who a card payment is paid to, and what the player is told when we cannot say.
 * A recipient we do not have is <b>our</b> problem — a 502 with an ERROR in the log — and
 * never a card decline that would send the player off to try another card.
 */
class PaymentRecipientTest {

    private static final UUID OWNER_ID = UUID.randomUUID();

    private PaymentRepository paymentRepository;
    private OwnerLegalDetailsRepository legalDetails;
    private PaymentProvider provider;
    private PaymentService service;
    private Booking booking;
    private SavedCard card;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        legalDetails = mock(OwnerLegalDetailsRepository.class);
        provider = mock(PaymentProvider.class);
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.current()).thenReturn(PlatformSettings.builder().build());
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> {
            Payment row = inv.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            return row;
        });
        service = new PaymentService(paymentRepository, mock(BookingRepository.class), mock(UserRepository.class),
                mock(GameRepository.class), mock(GameParticipantRepository.class), mock(PaymentMapper.class),
                mock(GameService.class), mock(NotificationService.class), mock(AuditService.class),
                provider, settings, legalDetails);

        User owner = User.builder().id(OWNER_ID).name("Owner").build();
        Stadium stadium = Stadium.builder().id(UUID.randomUUID()).owner(owner).name("Arena").build();
        booking = Booking.builder().id(UUID.randomUUID()).status("pending")
                .user(User.builder().id(UUID.randomUUID()).build()).stadium(stadium).price(5_000_000L).build();
        card = SavedCard.builder().id(UUID.randomUUID()).providerToken("token").build();
    }

    private void ownerHas(OwnerLegalDetails.ContractStatus contract, String recipientId) {
        when(legalDetails.findById(OWNER_ID)).thenReturn(Optional.of(OwnerLegalDetails.builder()
                .userId(OWNER_ID).atmosContractStatus(contract).atmosRecipientId(recipientId).build()));
    }

    private ChargeRequest chargeSent() {
        ArgumentCaptor<ChargeRequest> sent = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(provider).charge(sent.capture());
        return sent.getValue();
    }

    private void charge() {
        service.chargeForBooking(booking, card, CardFeeSplit.toOwner(5_500_000L, 150));
    }

    @Test
    void aSignedOwnerIsPaidToTheirRecipient() {
        ownerHas(OwnerLegalDetails.ContractStatus.SIGNED, "11299");
        when(provider.charge(any())).thenThrow(new PaymentProviderException("STPIMS-ERR-058", "declined"));

        charge();

        assertThat(chargeSent().splits()).containsExactly(Split.owner("11299", 5_500_000L));
    }

    /** canReceivePayouts: a recipient id left behind on a contract that is no longer signed is not used. */
    @Test
    void anOwnerWithoutASignedContractHasNoRecipient() {
        ownerHas(OwnerLegalDetails.ContractStatus.PENDING, "11299");
        when(provider.charge(any())).thenThrow(new PaymentProviderException("STPIMS-ERR-058", "declined"));

        charge();

        assertThat(chargeSent().splits()).containsExactly(Split.owner(null, 5_500_000L));
    }

    @Test
    void anOwnerTheProviderCannotPayIsA502WithItsOwnMessageNeverADecline() {
        ownerHas(OwnerLegalDetails.ContractStatus.NONE, null);
        when(provider.charge(any())).thenThrow(
                new PaymentProviderException(AtmosCodes.OWNER_NOT_CONNECTED, "no recipient"));

        assertThatThrownBy(this::charge)
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage("Maydon egasi toʻlov qabul qilishga hali ulanmagan");
    }

    @Test
    void aMissingPlatformRecipientIsTheServiceMessageNeverADecline() {
        ownerHas(OwnerLegalDetails.ContractStatus.SIGNED, "11299");
        when(provider.charge(any())).thenThrow(
                new PaymentProviderException(AtmosCodes.RECIPIENT_MISSING, "no platform recipient"));

        assertThatThrownBy(this::charge)
                .isInstanceOf(ProviderUnavailableException.class)
                .hasMessage(PaymentService.PROVIDER_UNAVAILABLE);
    }

    /** A team join's charge step says so too, so the join answers with the owner's message. */
    @Test
    void aJoinChargeToAnOwnerNotConnectedSaysSoAndCarriesNoDecline() {
        ownerHas(OwnerLegalDetails.ContractStatus.NONE, null);
        uz.minipolya.minipolya_backend.entity.Game game = uz.minipolya.minipolya_backend.entity.Game.builder()
                .id(UUID.randomUUID()).stadium(booking.getStadium()).build();
        uz.minipolya.minipolya_backend.entity.GameTeam joining = uz.minipolya.minipolya_backend.entity.GameTeam.builder()
                .id(UUID.randomUUID()).game(game).build();
        Payment row = Payment.builder().id(UUID.randomUUID()).gameTeam(joining).amount(5_500_000L)
                .ownerAmount(5_500_000L).platformAmount(0L).status(Payment.Status.PROCESSING)
                .method(Payment.Method.CARD).build();
        when(paymentRepository.findForUpdate(row.getId())).thenReturn(Optional.of(row));
        when(provider.charge(any())).thenThrow(
                new PaymentProviderException(AtmosCodes.OWNER_NOT_CONNECTED, "no recipient"));

        assertThat(service.createCommittedCharge(row.getId(), card))
                .isEqualTo(PaymentService.StepOutcome.OWNER_NOT_CONNECTED);
        assertThat(row.getStatus()).isEqualTo(Payment.Status.FAILED);
        assertThat(row.getDeclineReason()).isNull();
    }
}
