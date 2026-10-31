package uz.minipolya.minipolya_backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import tools.jackson.databind.ObjectMapper;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The filter loads the user on every authenticated request so that blocking
 * takes effect immediately rather than when the token expires.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtAuthFilterTest {

    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private JwtProvider jwtProvider;
    @Mock
    private UserRepository userRepository;
    @Mock
    private FilterChain filterChain;

    private JwtAuthFilter filter() {
        return new JwtAuthFilter(jwtProvider, userRepository, new ObjectMapper());
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private HttpServletRequest requestWithToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer valid-token");
        when(jwtProvider.isValid("valid-token")).thenReturn(true);
        when(jwtProvider.getUserId("valid-token")).thenReturn(USER_ID);
        return request;
    }

    @Test
    void blockedUserWithAStillValidTokenIsRejectedWith403() throws Exception {
        HttpServletRequest request = requestWithToken();
        HttpServletResponse response = new MockHttpServletResponse();
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.builder().id(USER_ID).name("Blocked").isBlocked(true).build()));

        filter().doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(((MockHttpServletResponse) response).getContentAsString())
                .contains("Account is blocked")
                .contains("\"success\":false");
        // The request must not reach the application, and must not be authenticated.
        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void unblockedUserIsAuthenticatedAndPassedThrough() throws Exception {
        HttpServletRequest request = requestWithToken();
        HttpServletResponse response = new MockHttpServletResponse();
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(User.builder().id(USER_ID).name("Active").isBlocked(false).build()));
        when(jwtProvider.isOwner("valid-token")).thenReturn(false);
        when(jwtProvider.isAdmin("valid-token")).thenReturn(false);

        filter().doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(200);
        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(USER_ID);
    }

    @Test
    void aTokenNamingAnUnknownUserIsTreatedAsUnauthenticated() throws Exception {
        HttpServletRequest request = requestWithToken();
        HttpServletResponse response = new MockHttpServletResponse();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        filter().doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void noTokenMeansNoUserLookupAtAll() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        HttpServletResponse response = new MockHttpServletResponse();

        filter().doFilterInternal(request, response, filterChain);

        verify(userRepository, never()).findById(USER_ID);
        verify(filterChain).doFilter(request, response);
    }

    // ---- Revocation after a password reset in the database ----

    /** A real provider, so the token carries a real, whole-second iat claim. */
    private final JwtProvider realProvider = realProvider();

    private static JwtProvider realProvider() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret("0123456789abcdef0123456789abcdef");
        return new JwtProvider(props);
    }

    private MockHttpServletRequest requestWith(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return request;
    }

    private User admin(OffsetDateTime passwordChangedAt) {
        return User.builder().id(USER_ID).name("Admin").isAdmin(true).passwordChangedAt(passwordChangedAt).build();
    }

    @Test
    void aTokenIssuedBeforeThePasswordChangeIsRevoked() throws Exception {
        String oldToken = realProvider.generateToken(admin(null));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                admin(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2))));
        MockHttpServletRequest request = requestWith(oldToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JwtAuthFilter(realProvider, userRepository, new ObjectMapper())
                .doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("\"code\":\"TOKEN_REVOKED\"")
                .contains("\"success\":false");
        verify(filterChain, never()).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void aTokenIssuedAfterThePasswordChangeIsAccepted() throws Exception {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                admin(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2))));
        String freshToken = realProvider.generateToken(admin(null));
        MockHttpServletRequest request = requestWith(freshToken);
        MockHttpServletResponse response = new MockHttpServletResponse();

        new JwtAuthFilter(realProvider, userRepository, new ObjectMapper())
                .doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(USER_ID);
    }

    @Test
    void anOlderTokenFromTheSameSecondIsStillRevoked() throws Exception {
        // Whole-second iat could not tell these apart; the millisecond issue time can.
        Instant second = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        HttpServletRequest request = requestWithToken();
        when(jwtProvider.getIssuedAt("valid-token")).thenReturn(second.plusMillis(200));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                admin(OffsetDateTime.ofInstant(second.plusMillis(700), ZoneOffset.UTC))));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        verify(filterChain, never()).doFilter(request, response);
    }

    @Test
    void aSignInInTheSameMillisecondAsTheResetIsAccepted() throws Exception {
        // password_changed_at keeps microseconds; the token has milliseconds.
        Instant stamp = Instant.parse("2026-10-28T10:00:00.700400Z");
        HttpServletRequest request = requestWithToken();
        when(jwtProvider.getIssuedAt("valid-token")).thenReturn(Instant.parse("2026-10-28T10:00:00.700Z"));
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(
                admin(OffsetDateTime.ofInstant(stamp, ZoneOffset.UTC))));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void aUserWhoNeverChangedAPasswordIsNotChecked() throws Exception {
        HttpServletRequest request = requestWithToken();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(admin(null)));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilterInternal(request, response, filterChain);

        verify(jwtProvider, never()).getIssuedAt("valid-token");
        verify(filterChain).doFilter(request, response);
    }
}
