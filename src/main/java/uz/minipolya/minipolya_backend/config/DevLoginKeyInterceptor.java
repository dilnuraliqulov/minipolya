package uz.minipolya.minipolya_backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The dev-only sign-in answers only a caller that knows {@code DEV_LOGIN_KEY}: every
 * {@code /api/auth/dev/**} request must send it in {@value #HEADER}. A missing or wrong key
 * gets exactly what an unknown path gets — 404 "Not found" — before the body is even read,
 * so nothing tells a caller the endpoints exist. The comparison is constant time: both
 * values are hashed first, so neither their content nor their length shows in the timing.
 */
@Configuration
@Profile("dev")
public class DevLoginKeyInterceptor implements HandlerInterceptor, WebMvcConfigurer {

    public static final String HEADER = "X-Dev-Login-Key";

    private final byte[] expectedDigest;

    public DevLoginKeyInterceptor(AppProperties properties) {
        // The same startup check as DevProfileGuard, whichever bean is created first.
        DevProfileGuard.requireLoginKey(properties.getDev().getLoginKey());
        this.expectedDigest = sha256(properties.getDev().getLoginKey());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/api/auth/dev/**");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws NoHandlerFoundException {
        if (!matches(request.getHeader(HEADER))) {
            throw new NoHandlerFoundException(request.getMethod(), request.getRequestURI(), new HttpHeaders());
        }
        return true;
    }

    boolean matches(String given) {
        return given != null && MessageDigest.isEqual(expectedDigest, sha256(given));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
