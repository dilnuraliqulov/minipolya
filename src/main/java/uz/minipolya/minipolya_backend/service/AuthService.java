package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.JwtProvider;
import uz.minipolya.minipolya_backend.dto.response.AuthResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.AccountBlockedException;
import uz.minipolya.minipolya_backend.mapper.UserMapper;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.TelegramAuthValidator;
import uz.minipolya.minipolya_backend.util.TelegramUserData;


/**
 * Authentication flows. Currently: Telegram Mini App {@code initData} →
 * find-or-create user → signed JWT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    /** Every new user starts in Uzbek, whatever Telegram's language is; they change it themselves. */
    static final String DEFAULT_LANGUAGE = "uz";

    private final TelegramAuthValidator telegramAuthValidator;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final JwtProvider jwtProvider;
    private final AccountBlockService accountBlockService;

    @Transactional
    public AuthResponse authenticateTelegram(String initData) {
        TelegramUserData data = telegramAuthValidator.validate(initData);

        User user = userRepository.findByTelegramId(data.telegramId())
                .orElseGet(() -> createFromTelegram(data));

        AuthResponse response = signIn(user);
        log.info("Telegram auth succeeded for userId={} telegramId={}", user.getId(), user.getTelegramId());
        return response;
    }

    /**
     * The token and user for a sign-in — the Telegram one and the dev-only one alike. A
     * blocked user is refused with 403 ACCOUNT_BLOCKED and why and when.
     */
    public AuthResponse signIn(User user) {
        if (user.isBlocked()) {
            log.warn("Blocked user attempted to sign in: userId={} telegramId={}", user.getId(), user.getTelegramId());
            throw new AccountBlockedException(accountBlockService.details(user.getId()));
        }
        return AuthResponse.builder()
                .token(jwtProvider.generateToken(user))
                .user(userMapper.toResponse(user))
                .build();
    }

    private User createFromTelegram(TelegramUserData data) {
        User user = User.builder()
                .telegramId(data.telegramId())
                .name(data.displayName())
                .language(DEFAULT_LANGUAGE)
                .build();
        User saved = userRepository.save(user);
        log.info("Created new user from Telegram: userId={} telegramId={}", saved.getId(), saved.getTelegramId());
        return saved;
    }
}
