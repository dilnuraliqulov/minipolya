package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.dto.request.AddCardRequest;
import uz.minipolya.minipolya_backend.dto.request.ConfirmCardRequest;
import uz.minipolya.minipolya_backend.dto.response.CardBindingResponse;
import uz.minipolya.minipolya_backend.dto.response.SavedCardResponse;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.SavedCardMapper;
import uz.minipolya.minipolya_backend.mapper.SavedCardMapperImpl;
import uz.minipolya.minipolya_backend.provider.BoundCard;
import uz.minipolya.minipolya_backend.provider.CardBinding;
import uz.minipolya.minipolya_backend.provider.PaymentProvider;
import uz.minipolya.minipolya_backend.repository.SavedCardRepository;
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

class SavedCardServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();
    private static final UUID CARD_ID = UUID.randomUUID();
    private static final String PAN = "8600123412341234";

    private SavedCardRepository repository;
    private UserRepository userRepository;
    private PaymentProvider paymentProvider;
    private SavedCardService service;
    private User user;

    @BeforeEach
    void setUp() {
        repository = mock(SavedCardRepository.class);
        userRepository = mock(UserRepository.class);
        paymentProvider = mock(PaymentProvider.class);
        SavedCardMapper mapper = new SavedCardMapperImpl();
        service = new SavedCardService(repository, userRepository, paymentProvider, mapper);

        user = User.builder().id(USER_ID).name("Player").build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(repository.save(any(SavedCard.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private SavedCard card(UUID ownerId) {
        return SavedCard.builder()
                .id(CARD_ID)
                .user(User.builder().id(ownerId).build())
                .providerToken("tok-1")
                .maskedNumber("8600 •••• 1234")
                .cardType("uzcard")
                .isActive(true)
                .build();
    }

    @Test
    void addingACardStartsABindingAndStoresNothingYet() {
        when(paymentProvider.startCardBinding(PAN, "12/29")).thenReturn(new CardBinding("bind-1"));

        CardBindingResponse response = service.addCard(USER_ID,
                AddCardRequest.builder().cardNumber(PAN).expiry("12/29").build());

        assertThat(response.getBindingId()).isEqualTo("bind-1");
        // Nothing is persisted until the SMS code confirms the binding.
        verify(repository, never()).save(any());
    }

    @Test
    void confirmingStoresTheTokenAndMaskedNumberAndNeverThePan() {
        when(paymentProvider.confirmCardBinding("bind-1", "111111"))
                .thenReturn(new BoundCard("tok-1", "8600 •••• 1234", "uzcard"));

        SavedCardResponse response = service.confirmCard(USER_ID,
                ConfirmCardRequest.builder().bindingId("bind-1").smsCode("111111").build());

        ArgumentCaptor<SavedCard> captor = ArgumentCaptor.forClass(SavedCard.class);
        verify(repository).save(captor.capture());
        SavedCard saved = captor.getValue();

        assertThat(saved.getProviderToken()).isEqualTo("tok-1");
        assertThat(saved.getMaskedNumber()).isEqualTo("8600 •••• 1234");
        assertThat(saved.getCardType()).isEqualTo("uzcard");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getUser().getId()).isEqualTo(USER_ID);

        assertThat(response.getMaskedNumber()).isEqualTo("8600 •••• 1234");
        assertThat(response.getCardType()).isEqualTo("uzcard");
        assertThat(response.isActive()).isTrue();
        // The response must not carry the provider token to the client at all.
        assertThat(response.toString()).doesNotContain("tok-1");
        assertThat(SavedCardResponse.class.getDeclaredFields())
                .noneMatch(f -> f.getName().toLowerCase().contains("token"));
    }

    @Test
    void listingReturnsOnlyMyActiveCards() {
        when(repository.findByUserIdAndIsActiveTrue(USER_ID)).thenReturn(List.of(card(USER_ID)));

        List<SavedCardResponse> cards = service.getMyCards(USER_ID);

        assertThat(cards).hasSize(1);
        assertThat(cards.get(0).getMaskedNumber()).isEqualTo("8600 •••• 1234");
    }

    @Test
    void deletingMarksTheCardInactiveRatherThanRemovingIt() {
        SavedCard mine = card(USER_ID);
        when(repository.findById(CARD_ID)).thenReturn(Optional.of(mine));

        service.deleteCard(USER_ID, CARD_ID);

        assertThat(mine.isActive()).isFalse();
        verify(repository).save(mine);
        verify(repository, never()).delete(any());
    }

    @Test
    void deletingSomeoneElsesCardIsForbidden() {
        when(repository.findById(CARD_ID)).thenReturn(Optional.of(card(OTHER_USER_ID)));

        assertThatThrownBy(() -> service.deleteCard(USER_ID, CARD_ID))
                .isInstanceOf(ForbiddenException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void deletingAnUnknownCardIsNotFound() {
        when(repository.findById(CARD_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteCard(USER_ID, CARD_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
