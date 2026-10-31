package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PlatformSettings;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.mapper.PaymentMapper;
import uz.minipolya.minipolya_backend.provider.ChargeRequest;
import uz.minipolya.minipolya_backend.provider.ClickProvider;
import uz.minipolya.minipolya_backend.provider.PaymeProvider;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.repository.OwnerLegalDetailsRepository;
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

/**
 * Friends (casual) games keep their rule — each player pays their share as they go, the
 * slot is booked once everyone has, nothing is held — and now pay it from a saved card
 * through ATMOS: charged now, grossed up by the card fee, reversed in full on a cancel.
 */
class CasualGameCardPaymentTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();

    private final PaymentRepository paymentRepository = mock(PaymentRepository.class);
    private final GameRepository gameRepository = mock(GameRepository.class);
    private final GameParticipantRepository participants = mock(GameParticipantRepository.class);
    private final PaymentProvider provider = mock(PaymentProvider.class);
    private final GameService gameService = mock(GameService.class);
    private PaymentService service;
    private GameParticipant participant;
    private SavedCard card;

    @BeforeEach
    void setUp() {
        PlatformSettingsService settings = mock(PlatformSettingsService.class);
        when(settings.current()).thenReturn(PlatformSettings.builder().build());
        service = new PaymentService(paymentRepository, mock(BookingRepository.class), mock(UserRepository.class),
                gameRepository, participants, mock(PaymentMapper.class),
                List.of(new PaymeProvider(), new ClickProvider()), gameService,
                mock(NotificationService.class), mock(AuditService.class), provider, settings, ProviderStubs.ownersPaidTo("rcp-owner"));

        User player = User.builder().id(PLAYER_ID).name("Player").build();
        User owner = User.builder().id(UUID.randomUUID()).name("Owner").build();
        Stadium stadium = Stadium.builder().id(UUID.randomUUID()).name("Arena").owner(owner).build();
        // 200 000 so'm between 10 players: a 20 000 so'm share each.
        Game game = Game.builder().id(GAME_ID).type("casual").status("open").stadium(stadium)
                .maxPlayers(10).totalPrice(20_000_000L).serviceFeePerPerson(0L).build();
        participant = GameParticipant.builder().id(UUID.randomUUID()).game(game).user(player)
                .status(GameParticipant.Status.JOINED).build();
        card = SavedCard.builder().id(UUID.randomUUID()).user(player).providerToken("tok").isActive(true).build();

        when(gameRepository.findById(GAME_ID)).thenReturn(Optional.of(game));
        when(participants.findByGameIdAndUserId(GAME_ID, PLAYER_ID)).thenReturn(Optional.of(participant));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(ProviderStubs.savedWithId());
        ProviderStubs.behaveLikeAtmos(provider);
    }

    @Test
    void theShareIsChargedNowGrossedUpAndTheGameHearsOfIt() {
        Payment payment = service.chargeGameShare(GAME_ID, PLAYER_ID, card);

        ArgumentCaptor<ChargeRequest> request = ArgumentCaptor.forClass(ChargeRequest.class);
        verify(provider).charge(request.capture());
        assertThat(request.getValue().total()).isEqualTo(TestPrices.card(2_000_000L));
        assertThat(payment.getStatus()).isEqualTo(Payment.Status.PAID);
        assertThat(payment.getPaymentType()).isEqualTo(Payment.PaymentType.GAME_SHARE);
        assertThat(participant.getPayment()).isSameAs(payment);
        verify(gameService).handleGamePaymentConfirmed(GAME_ID, PLAYER_ID);
        verify(provider, never()).hold(any());
    }

    @Test
    void aDeclinedShareDoesNotCountTowardsTheGame() {
        ProviderStubs.declineCharges(provider);

        Payment payment = service.chargeGameShare(GAME_ID, PLAYER_ID, card);

        assertThat(payment.getStatus()).isEqualTo(Payment.Status.FAILED);
        verify(gameService, never()).handleGamePaymentConfirmed(any(), any());
    }

    @Test
    void aShareCannotBePaidTwice() {
        participant.setStatus(GameParticipant.Status.PAID);

        assertThatThrownBy(() -> service.chargeGameShare(GAME_ID, PLAYER_ID, card))
                .hasMessage("Already paid");
    }

    @Test
    void aPaidShareIsReversedInFullWhenTheGameIsCalledOff() {
        Payment payment = service.chargeGameShare(GAME_ID, PLAYER_ID, card);

        Payment refund = service.refundInFull(payment, "Game cancelled by creator");

        verify(provider).reverse("succ-txn-" + payment.getId(), "Game cancelled by creator");
        assertThat(refund.getAmount()).isEqualTo(-TestPrices.card(2_000_000L));
    }
}
