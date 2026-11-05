package uz.minipolya.minipolya_backend.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

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
    private Cors cors = new Cors();
    private Dev dev = new Dev();
    private Uploads uploads = new Uploads();

    @Getter
    @Setter
    public static class Uploads {
        /**
         * UPLOADS_DIR: where uploaded files (stadium photos) are stored on the local filesystem,
         * served back under {@code /api/uploads/**}. Relative paths are taken from the working
         * directory. Back it up with the database: a photo row points at a file here.
         */
        private String dir = "./uploads";
    }

    @Getter
    @Setter
    public static class Dev {
        /**
         * DEV_LOGIN_KEY: the dev-only sign-in ({@code dev} profile) answers only requests that
         * send this value in {@code X-Dev-Login-Key}; anything else is 404. At least
         * {@link uz.minipolya.minipolya_backend.config.DevProfileGuard#MIN_LOGIN_KEY_LENGTH}
         * characters, or the application refuses to start under {@code dev}.
         */
        private String loginKey;
    }

    @Getter
    @Setter
    public static class Cors {
        /**
         * Browser origins allowed to call {@code /api/**} from another origin, e.g.
         * {@code https://admin.minipolya.uz} — exact origins, never {@code *}. Set with
         * {@code CORS_ALLOWED_ORIGINS} (comma-separated). Empty, the default, means no CORS
         * at all: only same-origin pages can call the API.
         */
        private List<String> allowedOrigins = new ArrayList<>();
    }

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
        /**
         * Master switch for every Telegram API call. Off: nothing is sent and nothing is
         * received (the bot behaves as if no token were set). Database tests turn it off so
         * they never message real chats with the token from {@code .env}.
         */
        private boolean enabled = true;
        /** TELEGRAM_BOT_MODE: {@code webhook} (production) or {@code polling} (dev profile only). */
        private String mode = "webhook";
        /**
         * TELEGRAM_WEBHOOK_SECRET: Telegram sends it back in {@code X-Telegram-Bot-Api-Secret-Token}
         * on every webhook call. 32+ characters of A-Z, a-z, 0-9, _ and -. Empty: every webhook
         * call is refused.
         */
        private String webhookSecret;
        /** MINIAPP_URL: the Mini App's https URL, opened by the bot's buttons and menu button. */
        private String miniappUrl;
        /** SUPPORT_USERNAME (optional, without @): the support contact shown by the bot. */
        private String supportUsername;
        /** The Bot API's base URL; overridable only for tests. */
        private String apiBaseUrl = "https://api.telegram.org";
        /** Run setMyDescription / setMyCommands / setChatMenuButton on startup (only what differs). */
        private boolean setupOnStartup = true;

        /** True when the bot can talk to Telegram: switched on and a token is set. */
        public boolean isActive() {
            return enabled && token != null && !token.isBlank();
        }

        public boolean isPolling() {
            return "polling".equalsIgnoreCase(mode == null ? "" : mode.trim());
        }

        /** Never render the token or the webhook secret. */
        @Override
        public String toString() {
            return "Bot(username=" + username + ", mode=" + mode + ", enabled=" + enabled + ")";
        }
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

        /** FakePaymentProvider's behaviour (dev and tests only; see "To simulate X in dev" in CLAUDE.md). */
        private Fake fake = new Fake();
    }

    /** {@code app.payments.fake.*}: how the fake provider imitates ATMOS. */
    @Getter
    @Setter
    public static class Fake {
        /** Digits in a binding's SMS code; any code of this length is accepted except 000000 / 999999. */
        private int otpLength = 6;
        /** How long an SMS code stays valid, in seconds. */
        private int otpTtlSeconds = 180;
        /** How long before a code may be sent again, in seconds. */
        private int resendCooldownSeconds = 60;
        /** What every new fake card holds, in tiyin (default 10 000 000 so'm). */
        private long startingBalance = 1_000_000_000L;
        /** The smallest transaction ATMOS takes, per split, in tiyin (500 so'm). */
        private long minAmount = 50_000L;
        /** How long after a payment it may still be reversed, in months. */
        private int refundWindowMonths = 3;
        /** A random delay before every call, in milliseconds (0 / 0 turns it off; tests do). */
        private long delayMinMs = 300;
        private long delayMaxMs = 800;
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
