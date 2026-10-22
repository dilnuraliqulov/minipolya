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
}
