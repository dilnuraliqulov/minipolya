package uz.minipolya.minipolya_backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Getter
@Setter
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Jwt jwt = new Jwt();
    private Bot bot = new Bot();
    private Booking booking = new Booking();
    private Game game = new Game();
    private Payments payments = new Payments();
    private AdminLogin adminLogin = new AdminLogin();

    @Getter
    @Setter
    public static class Jwt {
        private String secret;
        private long expirationMs = 86400000;
    }

    @Getter
    @Setter
    public static class Bot {
        private String token;
        /** Bot username (without @) used to build game invite deep links. */
        private String username;
    }

    @Getter
    @Setter
    public static class Booking {
        private int maxWeeksAhead = 4;
        private int holdExpiryMinutes = 10;
    }

    @Getter
    @Setter
    public static class Game {
        /**
         * How many hours before kick-off a team game stops accepting an
         * opponent: after this point a new team could not get there in time,
         * so nobody may join and a game still without an opponent is over.
         * team_vs_team only.
         */
        private int opponentDeadlineHours = 3;
    }

    @Getter
    @Setter
    public static class Payments {
        /**
         * Which card provider to wire up. {@code fake} selects
         * FakePaymentProvider, which refuses to start under the prod profile;
         * the real ATMOS client will register itself under its own value.
         */
        private String provider = "fake";
    }

    @Getter
    @Setter
    public static class AdminLogin {
        /** Wrong passwords in a row that lock an admin account. */
        private int maxFailedAttempts = 5;
        /** How long a locked account stays locked. */
        private int lockMinutes = 15;
        /** Sign-in attempts one IP address may make per window, right or wrong. */
        private int maxAttemptsPerIp = 20;
        private int ipWindowMinutes = 15;
    }
}
