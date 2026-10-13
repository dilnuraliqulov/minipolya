package uz.minipolya.minipolya_backend.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.User;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    // HS256 needs >= 32 bytes.
    private static final String SECRET = "0123456789abcdef0123456789abcdef";

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
                .id(42L)
                .phone("+998901234567")
                .isOwner(true)
                .isAdmin(false)
                .build();

        String token = jwtProvider.generateToken(user);

        assertThat(jwtProvider.isValid(token)).isTrue();
        assertThat(jwtProvider.getUserId(token)).isEqualTo(42L);
        assertThat(jwtProvider.getPhone(token)).isEqualTo("+998901234567");
        assertThat(jwtProvider.isOwner(token)).isTrue();
        assertThat(jwtProvider.isAdmin(token)).isFalse();
    }

    @Test
    void rejectsTamperedToken() {
        User user = User.builder().id(1L).build();
        String token = jwtProvider.generateToken(user);

        assertThat(jwtProvider.isValid(token + "tampered")).isFalse();
    }

    @Test
    void rejectsTokenSignedWithDifferentSecret() {
        AppProperties other = new AppProperties();
        other.getJwt().setSecret("ffffffffffffffffffffffffffffffff");
        String foreignToken = new JwtProvider(other).generateToken(User.builder().id(1L).build());

        assertThat(jwtProvider.isValid(foreignToken)).isFalse();
    }
}
