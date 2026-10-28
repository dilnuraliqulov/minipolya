package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import tools.jackson.databind.ObjectMapper;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.config.JwtProvider;
import uz.minipolya.minipolya_backend.dto.request.AdminLoginRequest;
import uz.minipolya.minipolya_backend.dto.request.ChangePasswordRequest;
import uz.minipolya.minipolya_backend.dto.response.AuthResponse;
import uz.minipolya.minipolya_backend.dto.response.UserResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.AccountBlockedException;
import uz.minipolya.minipolya_backend.exception.AccountLockedException;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.InvalidCredentialsException;
import uz.minipolya.minipolya_backend.mapper.UserMapper;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminAuthServiceTest {

    private static final UUID ADMIN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final String IP = "203.0.113.7";
    private static final String PASSWORD = "correct-horse-battery";

    /**
     * Made by PostgreSQL 17 pgcrypto with
     * {@code SELECT crypt('pgcrypto-made-password', gen_salt('bf', 12))} — exactly the
     * statement CLAUDE.md gives for creating an admin.
     */
    private static final String PGCRYPTO_HASH = "$2a$12$dFiIkBT8plqdewmWBVm19OWRiYSah.YgBp16.xl9U9j7mPw3oCiWO";
    private static final String PGCRYPTO_PASSWORD = "pgcrypto-made-password";

    @Mock
    private UserRepository userRepository;
    @Mock
    private JwtProvider jwtProvider;
    @Mock
    private UserMapper userMapper;
    @Mock
    private AuditService auditService;

    /** Cost 4 keeps the suite fast; verification reads the cost from each hash anyway. */
    private final BCryptPasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-28T09:00:00Z"));
    private AdminAuthService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        service = new AdminAuthService(userRepository, passwordEncoder,
                new LoginRateLimiter(clock, properties), jwtProvider, userMapper, auditService,
                new ObjectMapper(), clock, properties);
        when(jwtProvider.generateToken(any(User.class))).thenReturn("jwt-token");
        when(userMapper.toResponse(any(User.class)))
                .thenAnswer(inv -> UserResponse.builder().id(((User) inv.getArgument(0)).getId()).build());
    }

    // ---- Sign-in ----

    @Test
    void correctPasswordSignsInResetsTheCounterAndStoresTheLoginTime() {
        User admin = admin(PASSWORD);
        admin.setFailedLoginAttempts(3);
        givenUser("dilnur", admin);

        AuthResponse response = service.login(login("dilnur", PASSWORD), IP);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUser().getId()).isEqualTo(ADMIN_ID);
        assertThat(admin.getFailedLoginAttempts()).isZero();
        assertThat(admin.getLastLoginAt()).isEqualTo(now());
        verify(userRepository).save(admin);
        verify(auditService).log(eq(ADMIN_ID), eq("ADMIN_LOGIN_SUCCEEDED"), eq("user"), eq(ADMIN_ID),
                eq("{\"username\":\"dilnur\",\"ip\":\"203.0.113.7\"}"), eq(IP));
    }

    @Test
    void usernameIsMatchedAfterTrimming() {
        givenUser("dilnur", admin(PASSWORD));

        assertThat(service.login(login("  dilnur ", PASSWORD), IP).getToken()).isEqualTo("jwt-token");
    }

    @Test
    void wrongPasswordIs401AndCountsTowardsTheLockout() {
        User admin = admin(PASSWORD);
        givenUser("dilnur", admin);

        assertThatThrownBy(() -> service.login(login("dilnur", "wrong-password"), IP))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Login yoki parol noto'g'ri");

        assertThat(admin.getFailedLoginAttempts()).isEqualTo(1);
        assertThat(admin.getLockedUntil()).isNull();
        assertThat(admin.getLastLoginAt()).isNull();
        verify(jwtProvider, never()).generateToken(any(User.class));
        verify(auditService).log(eq(ADMIN_ID), eq("ADMIN_LOGIN_FAILED"), eq("user"), eq(ADMIN_ID),
                eq("{\"username\":\"dilnur\",\"ip\":\"203.0.113.7\",\"reason\":\"INVALID_CREDENTIALS\"}"), eq(IP));
    }

    @Test
    void unknownUsernameGetsTheSameAnswerAndStillPaysForABcryptCheck() {
        when(userRepository.findByUsernameForUpdate("ghost")).thenReturn(Optional.empty());

        Throwable unknown = catchThrowableOfType(InvalidCredentialsException.class,
                () -> service.login(login("ghost", PASSWORD), IP));

        assertThat(unknown).hasMessage("Login yoki parol noto'g'ri");
        // One BCrypt verification, the same work a real account costs.
        verify(passwordEncoder, times(1)).matches(eq(PASSWORD), anyString());
        verify(auditService).log(isNull(), eq("ADMIN_LOGIN_FAILED"), eq("user"), isNull(),
                eq("{\"username\":\"ghost\",\"ip\":\"203.0.113.7\",\"reason\":\"INVALID_CREDENTIALS\"}"), eq(IP));
    }

    @Test
    void unknownUsernameAndWrongPasswordAreIndistinguishable() {
        givenUser("dilnur", admin(PASSWORD));
        when(userRepository.findByUsernameForUpdate("ghost")).thenReturn(Optional.empty());

        InvalidCredentialsException wrongPassword = catchThrowableOfType(InvalidCredentialsException.class,
                () -> service.login(login("dilnur", "wrong-password"), IP));
        InvalidCredentialsException unknownUser = catchThrowableOfType(InvalidCredentialsException.class,
                () -> service.login(login("ghost", "wrong-password"), IP));

        assertThat(unknownUser.getStatus()).isEqualTo(wrongPassword.getStatus());
        assertThat(unknownUser.getCode()).isEqualTo(wrongPassword.getCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(unknownUser.getMessage()).isEqualTo(wrongPassword.getMessage());
        assertThat(unknownUser.getData()).isEqualTo(wrongPassword.getData());
    }

    @Test
    void fiveWrongPasswordsLockTheAccountForFifteenMinutes() {
        User admin = admin(PASSWORD);
        givenUser("dilnur", admin);

        for (int i = 1; i <= 4; i++) {
            assertThatThrownBy(() -> service.login(login("dilnur", "wrong-password"), IP))
                    .isInstanceOf(InvalidCredentialsException.class);
        }
        assertThat(admin.getFailedLoginAttempts()).isEqualTo(4);

        AccountLockedException fifth = catchThrowableOfType(AccountLockedException.class,
                () -> service.login(login("dilnur", "wrong-password"), IP));

        assertThat(fifth.getCode()).isEqualTo("ACCOUNT_LOCKED");
        assertThat(fifth.getMinutesRemaining()).isEqualTo(15);
        assertThat(admin.getLockedUntil()).isEqualTo(now().plusMinutes(15));
    }

    @Test
    void aLockedAccountRefusesEvenTheRightPasswordAndReportsMinutesLeft() {
        User admin = admin(PASSWORD);
        givenUser("dilnur", admin);
        lockOut();

        clock.advance(Duration.ofMinutes(5).plusSeconds(30));
        AccountLockedException locked = catchThrowableOfType(AccountLockedException.class,
                () -> service.login(login("dilnur", PASSWORD), IP));

        // 9.5 minutes left, rounded up.
        assertThat(locked.getMinutesRemaining()).isEqualTo(10);
        assertThat(admin.getLastLoginAt()).isNull();
        verify(jwtProvider, never()).generateToken(any(User.class));
    }

    @Test
    void theAccountUnlocksAfterFifteenMinutesWithFreshAttempts() {
        User admin = admin(PASSWORD);
        givenUser("dilnur", admin);
        lockOut();

        clock.advance(Duration.ofMinutes(15));
        AuthResponse response = service.login(login("dilnur", PASSWORD), IP);

        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(admin.getLockedUntil()).isNull();
        assertThat(admin.getFailedLoginAttempts()).isZero();
    }

    @Test
    void afterUnlockingAWrongPasswordStartsCountingFromOne() {
        User admin = admin(PASSWORD);
        givenUser("dilnur", admin);
        lockOut();

        clock.advance(Duration.ofMinutes(15));
        assertThatThrownBy(() -> service.login(login("dilnur", "wrong-password"), IP))
                .isInstanceOf(InvalidCredentialsException.class);

        assertThat(admin.getFailedLoginAttempts()).isEqualTo(1);
    }

    @Test
    void aNonAdminWithAPasswordCannotSignInThisWay() {
        User player = admin(PASSWORD);
        player.setAdmin(false);
        givenUser("player", player);

        assertThatThrownBy(() -> service.login(login("player", PASSWORD), IP))
                .isInstanceOf(InvalidCredentialsException.class)
                .hasMessage("Login yoki parol noto'g'ri");

        verify(jwtProvider, never()).generateToken(any(User.class));
        assertThat(player.getFailedLoginAttempts()).isZero();
        assertThat(player.getLastLoginAt()).isNull();
    }

    @Test
    void anAdminWithoutAPasswordCannotSignInThisWay() {
        User telegramAdmin = admin(PASSWORD);
        telegramAdmin.setPasswordHash(null);
        givenUser("tgadmin", telegramAdmin);

        assertThatThrownBy(() -> service.login(login("tgadmin", PASSWORD), IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void aBlockedAdminWithTheRightPasswordIs403() {
        User admin = admin(PASSWORD);
        admin.setBlocked(true);
        givenUser("dilnur", admin);

        assertThatThrownBy(() -> service.login(login("dilnur", PASSWORD), IP))
                .isInstanceOf(AccountBlockedException.class)
                .hasMessage("Account is blocked")
                .extracting("code").isEqualTo("ACCOUNT_BLOCKED");

        verify(jwtProvider, never()).generateToken(any(User.class));
        verify(auditService).log(eq(ADMIN_ID), eq("ADMIN_LOGIN_FAILED"), eq("user"), eq(ADMIN_ID),
                eq("{\"username\":\"dilnur\",\"ip\":\"203.0.113.7\",\"reason\":\"ACCOUNT_BLOCKED\"}"), eq(IP));
    }

    @Test
    void aBlockedAdminWithAWrongPasswordLearnsNothingAboutTheBlock() {
        User admin = admin(PASSWORD);
        admin.setBlocked(true);
        givenUser("dilnur", admin);

        assertThatThrownBy(() -> service.login(login("dilnur", "wrong-password"), IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void aHashMadeByPgcryptoVerifies() {
        assertThat(new BCryptPasswordEncoder(12).matches(PGCRYPTO_PASSWORD, PGCRYPTO_HASH)).isTrue();
        assertThat(new BCryptPasswordEncoder(12).matches("not-the-password", PGCRYPTO_HASH)).isFalse();
    }

    @Test
    void anAdminCreatedWithPgcryptoCanSignIn() {
        User admin = admin(PASSWORD);
        admin.setPasswordHash(PGCRYPTO_HASH);
        givenUser("dilnur", admin);

        assertThat(service.login(login("dilnur", PGCRYPTO_PASSWORD), IP).getToken()).isEqualTo("jwt-token");
    }

    @Test
    void anOverlongPasswordIsAWrongPasswordNotAServerError() {
        givenUser("dilnur", admin(PASSWORD));

        assertThatThrownBy(() -> service.login(login("dilnur", "x".repeat(200)), IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void noAuditEntryEverContainsThePassword() {
        givenUser("dilnur", admin(PASSWORD));
        when(userRepository.findByUsernameForUpdate("ghost")).thenReturn(Optional.empty());

        service.login(login("dilnur", PASSWORD), IP);
        catchThrowableOfType(InvalidCredentialsException.class,
                () -> service.login(login("dilnur", "wrong-password-123"), IP));
        catchThrowableOfType(InvalidCredentialsException.class,
                () -> service.login(login("ghost", "ghost-password-456"), IP));

        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(auditService, atLeastOnce()).log(any(), anyString(), anyString(), any(), details.capture(), any());
        assertThat(details.getAllValues())
                .hasSize(3)
                .noneMatch(d -> d.contains(PASSWORD) || d.contains("wrong-password-123")
                        || d.contains("ghost-password-456"));
    }

    @Test
    void theLoginRequestNeverPrintsThePassword() {
        assertThat(login("dilnur", PASSWORD).toString()).doesNotContain(PASSWORD).contains("dilnur");
    }

    // ---- Changing the password ----

    @Test
    void anAdminChangesTheirPassword() {
        User admin = admin(PASSWORD);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        service.changePassword(ADMIN_ID, change(PASSWORD, "a-brand-new-password"), IP);

        assertThat(passwordEncoder.matches("a-brand-new-password", admin.getPasswordHash())).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD, admin.getPasswordHash())).isFalse();
        assertThat(admin.getPasswordHash()).doesNotContain("a-brand-new-password");
        verify(userRepository).save(admin);
        verify(auditService).log(eq(ADMIN_ID), eq("ADMIN_PASSWORD_CHANGED"), eq("user"), eq(ADMIN_ID),
                eq("{\"username\":\"dilnur\",\"ip\":\"203.0.113.7\"}"), eq(IP));
    }

    @Test
    void changingThePasswordStampsTheTimeAndHandsBackAFreshToken() {
        User admin = admin(PASSWORD);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        AuthResponse response = service.changePassword(ADMIN_ID, change(PASSWORD, "a-brand-new-password"), IP);

        assertThat(admin.getPasswordChangedAt()).isEqualTo(now());
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getUser().getId()).isEqualTo(ADMIN_ID);
        // The token is made from the user as saved, i.e. after the stamp.
        verify(jwtProvider).generateToken(admin);
    }

    @Test
    void aRejectedChangeLeavesThePasswordChangedAtAlone() {
        User admin = admin(PASSWORD);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        catchThrowableOfType(BadRequestException.class,
                () -> service.changePassword(ADMIN_ID, change("not-it", "a-brand-new-password"), IP));

        assertThat(admin.getPasswordChangedAt()).isNull();
        verify(jwtProvider, never()).generateToken(any(User.class));
    }

    @Test
    void theNewPasswordWorksForSigningIn() {
        User admin = admin(PASSWORD);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        givenUser("dilnur", admin);

        service.changePassword(ADMIN_ID, change(PASSWORD, "a-brand-new-password"), IP);

        assertThat(service.login(login("dilnur", "a-brand-new-password"), IP).getToken()).isEqualTo("jwt-token");
        assertThatThrownBy(() -> service.login(login("dilnur", PASSWORD), IP))
                .isInstanceOf(InvalidCredentialsException.class);
    }

    @Test
    void aWrongCurrentPasswordIs400AndChangesNothing() {
        User admin = admin(PASSWORD);
        String before = admin.getPasswordHash();
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.changePassword(ADMIN_ID, change("not-it", "a-brand-new-password"), IP))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Joriy parol noto'g'ri");

        assertThat(admin.getPasswordHash()).isEqualTo(before);
        verify(userRepository, never()).save(any(User.class));
        verify(auditService, never()).log(any(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void anAdminWithoutAPasswordCannotSetOneThisWay() {
        User admin = admin(PASSWORD);
        admin.setPasswordHash(null);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.changePassword(ADMIN_ID, change("anything", "a-brand-new-password"), IP))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aNewPasswordBeyondBcryptsSeventyTwoBytesIsRejected() {
        User admin = admin(PASSWORD);
        when(userRepository.findById(ADMIN_ID)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.changePassword(ADMIN_ID, change(PASSWORD, "x".repeat(73)), IP))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("72 bytes");
    }

    // ---- Helpers ----

    private User admin(String password) {
        return User.builder()
                .id(ADMIN_ID)
                .name("Dilnur")
                .username("dilnur")
                .passwordHash(passwordEncoder.encode(password))
                .isAdmin(true)
                .build();
    }

    private void givenUser(String username, User user) {
        when(userRepository.findByUsernameForUpdate(username)).thenReturn(Optional.of(user));
    }

    private void lockOut() {
        for (int i = 1; i <= 5; i++) {
            catchThrowableOfType(RuntimeException.class,
                    () -> service.login(login("dilnur", "wrong-password"), IP));
        }
    }

    private OffsetDateTime now() {
        return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static AdminLoginRequest login(String username, String password) {
        return AdminLoginRequest.builder().username(username).password(password).build();
    }

    private static ChangePasswordRequest change(String current, String next) {
        return ChangePasswordRequest.builder().currentPassword(current).newPassword(next).build();
    }

    /** A clock the test moves forward by hand. */
    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
