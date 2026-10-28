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
    private Atmos atmos = new Atmos();
    private Security security = new Security();

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

        /**
         * The longest a team game's hold may last, in minutes (default 43 200 = 30 days).
         * A game whose hold would need longer cannot be created.
         */
        private int holdMaxMinutes = 43_200;
    }

    /**
     * The ATMOS client, read from the environment (see .env.example). Only used under the
     * {@code atmos} profile, where AtmosPaymentProvider refuses to start unless every
     * required value is set.
     */
    @Getter
    @Setter
    public static class Atmos {
        /** ATMOS_BASE_URL, e.g. https://apigw.atmos.uz */
        private String baseUrl;
        /** ATMOS_CONSUMER_KEY */
        private String consumerKey;
        /** ATMOS_CONSUMER_SECRET */
        private String consumerSecret;
        /** ATMOS_STORE_ID: our merchant store, used to pre-apply, apply and look up payments. */
        private String storeId;
        /** ATMOS_PLATFORM_RECIPIENT_ID: where the platform's split of every payment goes. */
        private String platformRecipientId;
        /** ATMOS_OFD_CODE_RENTAL (optional): IKPU code for the pitch-rental receipt line. */
        private String ofdCodeRental;
        /** ATMOS_OFD_CODE_SERVICE (optional): IKPU code for the platform-service receipt line. */
        private String ofdCodeService;
        /** How long to wait for ATMOS before treating a call as timed out. */
        private int timeoutSeconds = 30;

        /** Never render the credentials. */
        @Override
        public String toString() {
            return "Atmos(baseUrl=" + baseUrl + ", storeId=" + storeId + ")";
        }
    }

    @Getter
    @Setter
    public static class Security {
        /** CARD_TOKEN_KEY: 32 bytes, base64. Encrypts saved card tokens at rest. */
        private String cardTokenKey;

        @Override
        public String toString() {
            return "Security(cardTokenKey=****)";
        }
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
