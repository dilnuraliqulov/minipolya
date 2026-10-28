package uz.minipolya.minipolya_backend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;
import uz.minipolya.minipolya_backend.dto.response.ApiResponse;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Validates the {@code Authorization: Bearer <token>} header on every request.
 * On a valid token it populates the {@link SecurityContextHolder} with the
 * user id as principal and {@code ROLE_*} authorities derived from the claims.
 * On a missing/invalid token it simply leaves the context empty — the entry
 * point in {@link SecurityConfig} then returns 401 for protected endpoints.
 *
 * <p>The user is loaded on every authenticated request so that blocking takes
 * effect at once rather than when the token expires: a blocked user's token is
 * rejected here with 403, whatever the endpoint.
 *
 * <p>The same lookup revokes tokens on a password change: a token issued before
 * {@code users.password_changed_at} is refused with 401 {@code TOKEN_REVOKED}. Both are
 * compared to the millisecond ({@link JwtProvider#getIssuedAt}); the fresh token handed
 * out with the change is issued after the stamp, so it stays valid.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtProvider jwtProvider;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String token = resolveToken(request);
        if (token != null
                && SecurityContextHolder.getContext().getAuthentication() == null
                && jwtProvider.isValid(token)) {
            UUID userId = jwtProvider.getUserId(token);

            Optional<User> user = userRepository.findById(userId);
            if (user.isEmpty()) {
                // The token names a user that no longer exists: treat it as unauthenticated.
                filterChain.doFilter(request, response);
                return;
            }
            if (user.get().isBlocked()) {
                writeBlocked(response);
                return;
            }
            if (issuedBeforePasswordChange(token, user.get())) {
                writeRevoked(response);
                return;
            }

            List<SimpleGrantedAuthority> authorities = new ArrayList<>();
            authorities.add(new SimpleGrantedAuthority("ROLE_USER"));
            if (jwtProvider.isOwner(token)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_OWNER"));
            }
            if (jwtProvider.isAdmin(token)) {
                authorities.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
            }

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        filterChain.doFilter(request, response);
    }

    /** Same {@link ApiResponse} envelope the security handlers use, with 403. */
    private void writeBlocked(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error("Account is blocked"));
    }

    private boolean issuedBeforePasswordChange(String token, User user) {
        if (user.getPasswordChangedAt() == null) {
            return false;
        }
        Instant changedAt = user.getPasswordChangedAt().toInstant().truncatedTo(ChronoUnit.MILLIS);
        return jwtProvider.getIssuedAt(token).isBefore(changedAt);
    }

    /** 401 with a code, so the panel can tell "sign in again" from a plain missing token. */
    private void writeRevoked(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), ApiResponse.error("TOKEN_REVOKED",
                "Session ended because the password was changed; sign in again", null));
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return header.substring(BEARER_PREFIX.length()).trim();
        }
        return null;
    }
}
