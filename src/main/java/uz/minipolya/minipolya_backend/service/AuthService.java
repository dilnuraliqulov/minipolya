package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.config.JwtProvider;
import uz.minipolya.minipolya_backend.dto.response.AuthResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.mapper.UserMapper;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.util.TelegramAuthValidator;
import uz.minipolya.minipolya_backend.util.TelegramUserData;

import java.util.Set;

/**
 * Authentication flows. Currently: Telegram Mini App {@code initData} →
 * find-or-create user → signed JWT.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final Set<String> SUPPORTED_LANGUAGES = Set.of("uz", "ru", "en");

    private final TelegramAuthValidator telegramAuthValidator;
    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final JwtProvider jwtProvider;

    @Transactional
    public AuthResponse authenticateTelegram(String initData) {
        TelegramUserData data = telegramAuthValidator.validate(initData);

        User user = userRepository.findByTelegramId(data.telegramId())
                .orElseGet(() -> createFromTelegram(data));

        String token = jwtProvider.generateToken(user);
        log.info("Telegram auth succeeded for userId={} telegramId={}", user.getId(), user.getTelegramId());
        return AuthResponse.builder()
                .token(token)
                .user(userMapper.toResponse(user))
                .build();
    }

    private User createFromTelegram(TelegramUserData data) {
        User user = User.builder()
                .telegramId(data.telegramId())
                .name(data.displayName())
                .language(resolveLanguage(data.languageCode()))
                .build();
        User saved = userRepository.save(user);
        log.info("Created new user from Telegram: userId={} telegramId={}", saved.getId(), saved.getTelegramId());
        return saved;
    }

    private String resolveLanguage(String languageCode) {
        if (languageCode != null && SUPPORTED_LANGUAGES.contains(languageCode.toLowerCase())) {
            return languageCode.toLowerCase();
        }
        return "uz";
    }
}
