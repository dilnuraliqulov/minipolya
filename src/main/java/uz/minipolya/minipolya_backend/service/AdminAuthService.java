package uz.minipolya.minipolya_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.config.JwtProvider;
import uz.minipolya.minipolya_backend.dto.request.AdminLoginRequest;
import uz.minipolya.minipolya_backend.dto.request.ChangePasswordRequest;
import uz.minipolya.minipolya_backend.dto.response.AuthResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.AccountBlockedException;
import uz.minipolya.minipolya_backend.exception.AccountLockedException;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.InvalidCredentialsException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.UserMapper;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Username + password sign-in for platform admins, who are created directly in the
 * database (see CLAUDE.md, "Admin accounts"). Players and owners sign in through
 * Telegram in {@link AuthService}; nothing here touches that flow.
 *
 * <p>Order of checks on a sign-in, each chosen so a caller learns nothing it should not:
 * <ol>
 *   <li>the per-IP limit, before any work is done;</li>
 *   <li>an unknown username, a non-admin or an admin without a password all run a BCrypt
 *       check against a dummy hash and answer exactly like a wrong password;</li>
 *   <li>a locked account answers 423 without looking at the password;</li>
 *   <li>a wrong password counts towards the lockout;</li>
 *   <li>only with the right password is a blocked account told it is blocked.</li>
 * </ol>
 * Failed attempts must be remembered although the call ends in an exception, hence
 * {@code noRollbackFor}.
 */
@Slf4j
@Service
public class AdminAuthService {

    static final String AUDIT_LOGIN_SUCCEEDED = "ADMIN_LOGIN_SUCCEEDED";
    static final String AUDIT_LOGIN_FAILED = "ADMIN_LOGIN_FAILED";
    static final String AUDIT_PASSWORD_CHANGED = "ADMIN_PASSWORD_CHANGED";
    static final String AUDIT_ENTITY = "user";

    /** BCrypt ignores everything past 72 bytes, and Spring's encoder refuses longer input. */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter loginRateLimiter;
    private final JwtProvider jwtProvider;
    private final UserMapper userMapper;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final int maxFailedAttempts;
    private final Duration lockDuration;
    /** Hash of a random string, so an unknown username costs the same BCrypt work as a real one. */
    private final String dummyHash;

    public AdminAuthService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            LoginRateLimiter loginRateLimiter,
                            JwtProvider jwtProvider,
                            UserMapper userMapper,
                            AuditService auditService,
                            ObjectMapper objectMapper,
                            Clock clock,
                            AppProperties appProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginRateLimiter = loginRateLimiter;
        this.jwtProvider = jwtProvider;
        this.userMapper = userMapper;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.maxFailedAttempts = appProperties.getAdminLogin().getMaxFailedAttempts();
        this.lockDuration = Duration.ofMinutes(appProperties.getAdminLogin().getLockMinutes());
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional(noRollbackFor = {InvalidCredentialsException.class, AccountLockedException.class,
            AccountBlockedException.class})
    public AuthResponse login(AdminLoginRequest request, String ip) {
        loginRateLimiter.acquire(ip);

        String username = request.getUsername().trim();
        Optional<User> candidate = userRepository.findByUsernameForUpdate(username)
                .filter(u -> u.isAdmin() && u.getPasswordHash() != null);
        if (candidate.isEmpty()) {
            passwordEncoder.matches(request.getPassword(), dummyHash);
            auditFailure(null, username, ip, "INVALID_CREDENTIALS");
            log.warn("Admin sign-in failed from {}: no password admin with that username", ip);
            throw new InvalidCredentialsException();
        }

        User user = candidate.get();
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (isLocked(user, now)) {
            auditFailure(user, username, ip, "ACCOUNT_LOCKED");
            log.warn("Admin sign-in refused, account locked: userId={} ip={}", user.getId(), ip);
            throw new AccountLockedException(minutesUntil(now, user.getLockedUntil()));
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // Throws AccountLockedException itself on the attempt that reaches the limit.
            recordWrongPassword(user, username, ip, now);
            throw new InvalidCredentialsException();
        }

        if (user.isBlocked()) {
            auditFailure(user, username, ip, "ACCOUNT_BLOCKED");
            log.warn("Blocked admin attempted to sign in: userId={} ip={}", user.getId(), ip);
            throw new AccountBlockedException();
        }

        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        userRepository.save(user);

        auditService.log(user.getId(), AUDIT_LOGIN_SUCCEEDED, AUDIT_ENTITY, user.getId(),
                details(username, ip, null), ip);
        log.info("Admin signed in with a password: userId={} ip={}", user.getId(), ip);
        return AuthResponse.builder()
                .token(jwtProvider.generateToken(user))
                .user(userMapper.toResponse(user))
                .build();
    }

    /**
     * Sets a new password and stamps {@code password_changed_at}, which makes JwtAuthFilter
     * refuse every token issued before now — including the caller's. The returned token is
     * issued after the stamp, so the caller stays signed in.
     */
    @Transactional
    public AuthResponse changePassword(UUID adminUserId, ChangePasswordRequest request, String ip) {
        User user = userRepository.findById(adminUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.getPasswordHash() == null
                || !passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BadRequestException("Joriy parol noto'g'ri");
        }
        if (request.getNewPassword().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new BadRequestException("newPassword must be at most " + BCRYPT_MAX_BYTES + " bytes");
        }

        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setPasswordChangedAt(OffsetDateTime.now(clock));
        userRepository.save(user);

        auditService.log(user.getId(), AUDIT_PASSWORD_CHANGED, AUDIT_ENTITY, user.getId(),
                details(user.getUsername(), ip, null), ip);
        log.info("Admin changed their password: userId={} ip={}", user.getId(), ip);
        return AuthResponse.builder()
                .token(jwtProvider.generateToken(user))
                .user(userMapper.toResponse(user))
                .build();
    }

    // ---- Helpers ----

    private boolean isLocked(User user, OffsetDateTime now) {
        return user.getLockedUntil() != null && user.getLockedUntil().isAfter(now);
    }

    /** Counts the failure; the one that reaches the limit locks the account and answers 423. */
    private void recordWrongPassword(User user, String username, String ip, OffsetDateTime now) {
        int attempts = user.getFailedLoginAttempts() + 1;
        if (attempts >= maxFailedAttempts) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(now.plus(lockDuration));
            userRepository.save(user);
            auditFailure(user, username, ip, "ACCOUNT_LOCKED");
            log.warn("Admin account locked after {} wrong passwords: userId={} ip={}",
                    maxFailedAttempts, user.getId(), ip);
            throw new AccountLockedException(minutesUntil(now, user.getLockedUntil()));
        }
        user.setFailedLoginAttempts(attempts);
        userRepository.save(user);
        auditFailure(user, username, ip, "INVALID_CREDENTIALS");
        log.warn("Admin sign-in failed, wrong password ({} of {}): userId={} ip={}",
                attempts, maxFailedAttempts, user.getId(), ip);
    }

    private void auditFailure(User user, String username, String ip, String reason) {
        UUID userId = user == null ? null : user.getId();
        auditService.log(userId, AUDIT_LOGIN_FAILED, AUDIT_ENTITY, userId, details(username, ip, reason), ip);
    }

    /** JSON with the username and IP — never a password. Built by Jackson, so input is escaped. */
    private String details(String username, String ip, String reason) {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("username", username);
        details.put("ip", ip);
        if (reason != null) {
            details.put("reason", reason);
        }
        return objectMapper.writeValueAsString(details);
    }

    private static long minutesUntil(OffsetDateTime now, OffsetDateTime until) {
        return LoginRateLimiter.ceilMinutes(Duration.between(now, until));
    }
}
