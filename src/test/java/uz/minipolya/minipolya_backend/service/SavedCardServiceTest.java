package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.minipolya.minipolya_backend.dto.request.AddCardRequest;
import uz.minipolya.minipolya_backend.dto.request.ConfirmCardRequest;
import uz.minipolya.minipolya_backend.dto.request.ResendCardCodeRequest;
import uz.minipolya.minipolya_backend.exception.CardBindingException;
import uz.minipolya.minipolya_backend.exception.ProviderUnavailableException;
import uz.minipolya.minipolya_backend.provider.AtmosCodes;
import uz.minipolya.minipolya_backend.provider.PaymentProviderException;
import uz.minipolya.minipolya_backend.provider.ProviderTimeoutException;
import org.springframework.http.HttpStatus;
import uz.minipolya.minipolya_backend.dto.response.CardBindingResponse;
import uz.minipolya.minipolya_backend.dto.response.SavedCardResponse;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.SavedCardMapper;
import uz.minipolya.minipolya_backend.mapper.SavedCardMapperImpl;
import uz.minipolya.minipolya_backend.provider.BoundCard;
import uz.minipolya.minipolya_backend.provider.CardBindingStart;
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
        when(paymentProvider.bindCardInit(PAN, "12/29")).thenReturn(new CardBindingStart("bind-1", "********9999"));

        CardBindingResponse response = service.addCard(USER_ID,
                AddCardRequest.builder().cardNumber(PAN).expiry("12/29").build());

        assertThat(response.getBindingId()).isEqualTo("bind-1");
        assertThat(response.getMaskedPhone()).isEqualTo("********9999");
        // Nothing is persisted until the SMS code confirms the binding.
        verify(repository, never()).save(any());
    }

    @Test
    void aRefusedBindingIsA400WithACodeThePlayerCanActOn() {
        when(paymentProvider.bindCardInit(any(), any()))
                .thenThrow(new PaymentProviderException(AtmosCodes.WRONG_CARD_NUMBER, "Неправильный номер карты"));

        assertThatThrownBy(() -> service.addCard(USER_ID,
                AddCardRequest.builder().cardNumber("4111111111111111").expiry("12/29").build()))
                .isInstanceOfSatisfying(CardBindingException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getCode()).isEqualTo("CARD_NOT_SUPPORTED");
                    assertThat(ex.getMessage()).isEqualTo("Faqat Uzcard va Humo kartalari qabul qilinadi");
                });
    }

    @Test
    void everyBindingCodeHasItsOwnAnswer() {
        assertThat(SavedCardService.bindingError(AtmosCodes.WRONG_EXPIRY).getCode()).isEqualTo("CARD_EXPIRY_INVALID");
        assertThat(SavedCardService.bindingError(AtmosCodes.CARD_EXPIRED).getCode()).isEqualTo("CARD_EXPIRED");
        assertThat(SavedCardService.bindingError(AtmosCodes.WRONG_OTP).getCode()).isEqualTo("SMS_CODE_WRONG");
        assertThat(SavedCardService.bindingError(AtmosCodes.OTP_EXPIRED).getCode()).isEqualTo("SMS_CODE_EXPIRED");
        assertThat(SavedCardService.bindingError(AtmosCodes.OTP_RESEND_TOO_EARLY).getCode()).isEqualTo("SMS_RESEND_TOO_EARLY");
        assertThat(SavedCardService.bindingError(AtmosCodes.RESEND_UNSUPPORTED).getCode()).isEqualTo("SMS_RESEND_UNAVAILABLE");
        assertThat(SavedCardService.bindingError(AtmosCodes.BINDING_NOT_FOUND).getCode()).isEqualTo("BINDING_NOT_FOUND");
        assertThat(SavedCardService.bindingError("STPIMS-ERR-999").getCode()).isEqualTo("CARD_BINDING_FAILED");
    }

    @Test
    void anUnreachableProviderIs502WhileBinding() {
        when(paymentProvider.bindCardConfirm(any(), any()))
                .thenThrow(new PaymentProviderException(AtmosCodes.SERVICE_UNAVAILABLE, "ATMOS refused"));
        when(paymentProvider.bindCardInit(any(), any()))
                .thenThrow(new ProviderTimeoutException("bind-card/init", null));

        assertThatThrownBy(() -> service.confirmCard(USER_ID,
                ConfirmCardRequest.builder().bindingId("bind-1").smsCode("123456").build()))
                .isInstanceOf(ProviderUnavailableException.class);
        assertThatThrownBy(() -> service.addCard(USER_ID,
                AddCardRequest.builder().cardNumber(PAN).expiry("12/29").build()))
                .isInstanceOf(ProviderUnavailableException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void theCodeCanBeResent() {
        when(paymentProvider.resendBindingCode("bind-1")).thenReturn(new CardBindingStart("bind-1", "+998 ** *** ** 58"));

        CardBindingResponse response = service.resendCode(USER_ID, ResendCardCodeRequest.builder().bindingId("bind-1").build());

        assertThat(response.getBindingId()).isEqualTo("bind-1");
        assertThat(response.getMaskedPhone()).isEqualTo("+998 ** *** ** 58");
    }

    @Test
    void confirmingStoresTheTokenAndMaskedNumberAndNeverThePan() {
        when(paymentProvider.bindCardConfirm("bind-1", "111111"))
                .thenReturn(new BoundCard("tok-1", "card-77", "8600 •••• 1234", "12/30"));

        SavedCardResponse response = service.confirmCard(USER_ID,
                ConfirmCardRequest.builder().bindingId("bind-1").smsCode("111111").build());

        ArgumentCaptor<SavedCard> captor = ArgumentCaptor.forClass(SavedCard.class);
        verify(repository).save(captor.capture());
        SavedCard saved = captor.getValue();

        assertThat(saved.getProviderToken()).isEqualTo("tok-1");
        assertThat(saved.getMaskedNumber()).isEqualTo("8600 •••• 1234");
        assertThat(saved.getCardType()).isEqualTo("uzcard");
        assertThat(saved.getProviderCardId()).isEqualTo("card-77");
        assertThat(saved.getExpiry()).isEqualTo("12/30");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getUser().getId()).isEqualTo(USER_ID);

        assertThat(response.getMaskedNumber()).isEqualTo("8600 •••• 1234");
        assertThat(response.getCardType()).isEqualTo("uzcard");
        assertThat(response.getExpiry()).isEqualTo("12/30");
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
    void deletingACardAlsoRemovesItAtTheProvider() {
        SavedCard mine = card(USER_ID);
        mine.setProviderCardId("card-77");
        when(repository.findById(CARD_ID)).thenReturn(Optional.of(mine));

        service.deleteCard(USER_ID, CARD_ID);

        verify(paymentProvider).removeCard("card-77", "tok-1");
        assertThat(mine.isActive()).isFalse();
    }

    @Test
    void aProviderThatCannotRemoveTheCardDoesNotKeepItUsable() {
        SavedCard mine = card(USER_ID);
        mine.setProviderCardId("card-77");
        when(repository.findById(CARD_ID)).thenReturn(Optional.of(mine));
        org.mockito.Mockito.doThrow(new uz.minipolya.minipolya_backend.provider.PaymentProviderException("X", "no"))
                .when(paymentProvider).removeCard("card-77", "tok-1");

        service.deleteCard(USER_ID, CARD_ID);

        assertThat(mine.isActive()).isFalse();
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
