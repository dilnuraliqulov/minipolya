package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.response.AuthResponse;
import uz.minipolya.minipolya_backend.dto.response.DevUserResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.UUID;

/**
 * Dev-only sign-in for testing the Mini App in a plain browser, without Telegram
 * initData. Exists only under the {@code dev} profile, which refuses to start next to
 * {@code prod} or {@code atmos} ({@code DevProfileGuard}).
 */
@Slf4j
@Service
@Profile("dev")
@RequiredArgsConstructor
public class DevAuthService {

    private final UserRepository userRepository;
    private final AuthService authService;

    /** The 50 newest users, to pick from. */
    @Transactional(readOnly = true)
    public List<DevUserResponse> users() {
        return userRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .map(user -> DevUserResponse.builder()
                        .id(user.getId())
                        .name(user.getName())
                        .owner(user.isOwner())
                        .admin(user.isAdmin())
                        .blocked(user.isBlocked())
                        .build())
                .toList();
    }

    /** The same token and user the Telegram sign-in returns; a blocked user gets the same 403. */
    @Transactional(readOnly = true)
    public AuthResponse login(String userId) {
        UUID id;
        try {
            id = UUID.fromString(userId == null ? "" : userId.trim());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Invalid id");
        }
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        log.warn("DEV sign-in as userId={}", user.getId());
        return authService.signIn(user);
    }
}
