package uz.minipolya.minipolya_backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.config.JwtProvider;
import uz.minipolya.minipolya_backend.dto.request.AdminLoginRequest;
import uz.minipolya.minipolya_backend.dto.response.AuthResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.AccountBlockedException;
import uz.minipolya.minipolya_backend.exception.AccountLockedException;
import uz.minipolya.minipolya_backend.exception.InvalidCredentialsException;
import uz.minipolya.minipolya_backend.mapper.UserMapper;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * Username + password sign-in for platform admins, who are created directly in the
 * database (see CLAUDE.md, "Admin accounts"). Players and owners sign in through
 * Telegram in {@link AuthService}; nothing here touches that flow. There is deliberately
 * no way to change a password through the API: passwords are set and reset only in the
 * database, and a reset that stamps {@code password_changed_at} signs out every session.
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

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginRateLimiter loginRateLimiter;
    private final JwtProvider jwtProvider;
    private final UserMapper userMapper;
    private final AuditService auditService;
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
                            Clock clock,
                            AppProperties appProperties) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginRateLimiter = loginRateLimiter;
        this.jwtProvider = jwtProvider;
        this.userMapper = userMapper;
        this.auditService = auditService;
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
            auditFailure(null, username, ip, "INVALID_CREDENTIALS", null, null);
            log.warn("Admin sign-in failed from {}: no password admin with that username", ip);
            throw new InvalidCredentialsException();
        }

        User user = candidate.get();
        OffsetDateTime now = OffsetDateTime.now(clock);

        if (isLocked(user, now)) {
            auditFailure(user, username, ip, "ACCOUNT_LOCKED", null, null);
            log.warn("Admin sign-in refused, account locked: userId={} ip={}", user.getId(), ip);
            throw new AccountLockedException(minutesUntil(now, user.getLockedUntil()));
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            // Throws AccountLockedException itself on the attempt that reaches the limit.
            recordWrongPassword(user, username, ip, now);
            throw new InvalidCredentialsException();
        }

        if (user.isBlocked()) {
            auditFailure(user, username, ip, "ACCOUNT_BLOCKED", null, null);
            log.warn("Blocked admin attempted to sign in: userId={} ip={}", user.getId(), ip);
            throw new AccountBlockedException();
        }

        AuditEntry audit = AuditEntry.admin(user.getId(), AuditAction.ADMIN_LOGIN_SUCCEEDED)
                .entity(AuditEntityType.USER, user.getId(), AuditLabels.user(user))
                .change("failedLoginAttempts", user.getFailedLoginAttempts(), 0)
                .change("lockedUntil", user.getLockedUntil(), null)
                .change("lastLoginAt", user.getLastLoginAt(), now)
                .ip(ip);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        userRepository.save(user);
        auditService.record(audit);
        log.info("Admin signed in with a password: userId={} ip={}", user.getId(), ip);
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
        int before = user.getFailedLoginAttempts();
        OffsetDateTime lockedBefore = user.getLockedUntil();
        int attempts = before + 1;
        if (attempts >= maxFailedAttempts) {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(now.plus(lockDuration));
            userRepository.save(user);
            auditFailure(user, username, ip, "ACCOUNT_LOCKED", before, lockedBefore);
            log.warn("Admin account locked after {} wrong passwords: userId={} ip={}",
                    maxFailedAttempts, user.getId(), ip);
            throw new AccountLockedException(minutesUntil(now, user.getLockedUntil()));
        }
        user.setFailedLoginAttempts(attempts);
        userRepository.save(user);
        auditFailure(user, username, ip, "INVALID_CREDENTIALS", before, lockedBefore);
        log.warn("Admin sign-in failed, wrong password ({} of {}): userId={} ip={}",
                attempts, maxFailedAttempts, user.getId(), ip);
    }

    /**
     * One ADMIN_LOGIN_FAILED entry with the reason, the IP and — never a password — the
     * username: as the entity label when no such admin exists, so attempts on made-up
     * names are still visible. {@code attemptsBefore} is set when the attempt changed the
     * lockout counter, which then appears in the changes (and lockedUntil, when it locks).
     */
    private void auditFailure(User user, String username, String ip, String reason,
                              Integer attemptsBefore, OffsetDateTime lockedBefore) {
        AuditEntry entry = AuditEntry.admin(user == null ? null : user.getId(), AuditAction.ADMIN_LOGIN_FAILED)
                .entity(AuditEntityType.USER, user == null ? null : user.getId(),
                        user == null ? username : AuditLabels.user(user))
                .reason(reason)
                .ip(ip);
        if (user != null && attemptsBefore != null) {
            entry.change("failedLoginAttempts", attemptsBefore, user.getFailedLoginAttempts())
                    .change("lockedUntil", lockedBefore, user.getLockedUntil());
        }
        auditService.record(entry);
    }

    private static long minutesUntil(OffsetDateTime now, OffsetDateTime until) {
        return LoginRateLimiter.ceilMinutes(Duration.between(now, until));
    }
}
