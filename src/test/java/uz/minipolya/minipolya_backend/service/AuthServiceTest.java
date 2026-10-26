package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.minipolya.minipolya_backend.config.JwtProvider;
import uz.minipolya.minipolya_backend.dto.response.AuthResponse;
import uz.minipolya.minipolya_backend.dto.response.UserResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.mapper.UserMapper;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.TelegramAuthValidator;
import uz.minipolya.minipolya_backend.util.TelegramUserData;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final UUID USER_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID USER_ID_5 = UUID.fromString("00000000-0000-0000-0000-000000000005");

    @Mock
    private TelegramAuthValidator telegramAuthValidator;
    @Mock
    private UserRepository userRepository;
    @Mock
    private UserMapper userMapper;
    @Mock
    private JwtProvider jwtProvider;

    @InjectMocks
    private AuthService authService;

    @Test
    void createsUserWhenTelegramIdIsNew() {
        TelegramUserData data = new TelegramUserData(777L, "Ali", "Valiyev", "ali", "ru");
        when(telegramAuthValidator.validate("init")).thenReturn(data);
        when(userRepository.findByTelegramId(777L)).thenReturn(Optional.empty());

        User saved = User.builder().id(USER_ID_1).telegramId(777L).name("Ali Valiyev").language("ru").build();
        when(userRepository.save(any(User.class))).thenReturn(saved);
        when(jwtProvider.generateToken(saved)).thenReturn("jwt-token");
        UserResponse mapped = UserResponse.builder().id(USER_ID_1).telegramId(777L).name("Ali Valiyev").build();
        when(userMapper.toResponse(saved)).thenReturn(mapped);

        AuthResponse result = authService.authenticateTelegram("init");

        assertThat(result.getToken()).isEqualTo("jwt-token");
        assertThat(result.getUser()).isEqualTo(mapped);
        verify(userRepository).save(any(User.class));
    }

    @Test
    void reusesExistingUserWithoutSaving() {
        TelegramUserData data = new TelegramUserData(777L, "Ali", null, "ali", "uz");
        when(telegramAuthValidator.validate("init")).thenReturn(data);
        User existing = User.builder().id(USER_ID_5).telegramId(777L).name("Ali").build();
        when(userRepository.findByTelegramId(777L)).thenReturn(Optional.of(existing));
        when(jwtProvider.generateToken(existing)).thenReturn("jwt-token");
        when(userMapper.toResponse(existing)).thenReturn(UserResponse.builder().id(USER_ID_5).build());

        AuthResponse result = authService.authenticateTelegram("init");

        assertThat(result.getToken()).isEqualTo("jwt-token");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void blockedUserCannotSignIn() {
        TelegramUserData data = new TelegramUserData(777L, "Ali", null, "ali", "uz");
        when(telegramAuthValidator.validate("init")).thenReturn(data);
        User blocked = User.builder().id(USER_ID_5).telegramId(777L).name("Ali").isBlocked(true).build();
        when(userRepository.findByTelegramId(777L)).thenReturn(Optional.of(blocked));

        assertThatThrownBy(() -> authService.authenticateTelegram("init"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Account is blocked");

        verify(jwtProvider, never()).generateToken(any(User.class));
    }

    @Test
    void unblockedUserCanSignInAgain() {
        TelegramUserData data = new TelegramUserData(777L, "Ali", null, "ali", "uz");
        when(telegramAuthValidator.validate("init")).thenReturn(data);
        User user = User.builder().id(USER_ID_5).telegramId(777L).name("Ali").isBlocked(false).build();
        when(userRepository.findByTelegramId(777L)).thenReturn(Optional.of(user));
        when(jwtProvider.generateToken(user)).thenReturn("jwt-token");
        when(userMapper.toResponse(user)).thenReturn(UserResponse.builder().id(USER_ID_5).build());

        assertThat(authService.authenticateTelegram("init").getToken()).isEqualTo("jwt-token");
    }
}
