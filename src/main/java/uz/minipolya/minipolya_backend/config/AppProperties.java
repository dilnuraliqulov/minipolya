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
    private Cancellation cancellation = new Cancellation();
    private Game game = new Game();

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
    public static class Cancellation {
        private int defaultFullRefundHours = 24;
        private int defaultPartialRefundHours = 12;
        private int defaultPartialRefundPercent = 50;
    }

    @Getter
    @Setter
    public static class Game {
        /** Tiyin: 500 000 tiyin = 5 000 so'm. */
        private long defaultServiceFeePerPerson = 500_000L;
        private int defaultCreatorSharePct = 50;
    }
}
