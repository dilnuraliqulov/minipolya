package uz.minipolya.minipolya_backend.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.User;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    // HS256 needs >= 32 bytes.
    private static final String SECRET = "0123456789abcdef0123456789abcdef";
    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID OTHER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        AppProperties props = new AppProperties();
        props.getJwt().setSecret(SECRET);
        props.getJwt().setExpirationMs(86_400_000L);
        jwtProvider = new JwtProvider(props);
    }

    @Test
    void generatesTokenCarryingUserClaims() {
        User user = User.builder()
                .id(USER_ID)
                .phone("+998901234567")
                .isOwner(true)
                .isAdmin(false)
                .build();

        String token = jwtProvider.generateToken(user);

        assertThat(jwtProvider.isValid(token)).isTrue();
        assertThat(jwtProvider.getUserId(token)).isEqualTo(USER_ID);
        assertThat(jwtProvider.getPhone(token)).isEqualTo("+998901234567");
        assertThat(jwtProvider.isOwner(token)).isTrue();
        assertThat(jwtProvider.isAdmin(token)).isFalse();
    }

    @Test
    void rejectsTamperedToken() {
        User user = User.builder().id(OTHER_ID).build();
        String token = jwtProvider.generateToken(user);

        assertThat(jwtProvider.isValid(token + "tampered")).isFalse();
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        AppProperties other = new AppProperties();
        other.getJwt().setSecret("ffffffffffffffffffffffffffffffff");
        String foreignToken = new JwtProvider(other).generateToken(User.builder().id(OTHER_ID).build());

        assertThat(jwtProvider.isValid(foreignToken)).isFalse();
    }

    @Test
    void exposesTheIssueTimeToTheMillisecond() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String token = jwtProvider.generateToken(User.builder().id(USER_ID).build());
        Instant after = Instant.now();

        Instant issuedAt = jwtProvider.getIssuedAt(token);

        assertThat(issuedAt).isBetween(before, after);
        assertThat(issuedAt.getNano() % 1_000_000).isZero();
    }

    @Test
    void aTokenWithoutTheMillisecondClaimFallsBackToIat() {
        Instant issued = Instant.parse("2026-10-28T10:00:00Z");
        String legacy = Jwts.builder()
                .subject(USER_ID.toString())
                .issuedAt(Date.from(issued))
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(
                        SECRET.getBytes(StandardCharsets.UTF_8)))
                .compact();

        assertThat(jwtProvider.getIssuedAt(legacy)).isEqualTo(issued);
    }
}
