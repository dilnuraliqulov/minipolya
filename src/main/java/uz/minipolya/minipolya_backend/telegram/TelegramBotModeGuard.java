package uz.minipolya.minipolya_backend.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import uz.minipolya.minipolya_backend.config.AppProperties;

import java.util.regex.Pattern;

/**
 * Refuses to start with a bot configuration that would be unsafe or silently broken:
 * <ul>
 *   <li>TELEGRAM_BOT_MODE other than {@code webhook} or {@code polling};</li>
 *   <li>{@code polling} outside the {@code dev} profile (so never in prod);</li>
 *   <li>under {@code prod} with the bot active: a TELEGRAM_WEBHOOK_SECRET shorter than 32
 *       characters or with characters Telegram does not allow, or a MINIAPP_URL that is not
 *       https (Telegram refuses web_app buttons without it).</li>
 * </ul>
 * Outside {@code prod} an unusable MINIAPP_URL is only a warning: the bot still talks, without
 * the open button.
 */
@Slf4j
@Configuration
public class TelegramBotModeGuard {

    /** Telegram's secret_token alphabet; 32 characters is our own floor. */
    static final Pattern SECRET = Pattern.compile("^[A-Za-z0-9_-]{32,256}$");

    public TelegramBotModeGuard(AppProperties appProperties, Environment environment) {
        check(appProperties.getBot(), environment);
    }

    static void check(AppProperties.Bot bot, Environment environment) {
        String mode = bot.getMode() == null ? "" : bot.getMode().trim().toLowerCase();
        if (!mode.equals("webhook") && !mode.equals("polling")) {
            throw new IllegalStateException("TELEGRAM_BOT_MODE must be webhook or polling");
        }
        if (bot.isPolling() && !environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException(
                    "TELEGRAM_BOT_MODE=polling is for local development only: it needs the dev profile");
        }
        if (bot.isActive() && environment.acceptsProfiles(Profiles.of("prod"))) {
            String secret = bot.getWebhookSecret();
            if (secret == null || !SECRET.matcher(secret).matches()) {
                throw new IllegalStateException(
                        "TELEGRAM_WEBHOOK_SECRET must be 32-256 characters of A-Z, a-z, 0-9, _ and -");
            }
            if (!MiniAppUrl.usable(bot.getMiniappUrl())) {
                throw new IllegalStateException("MINIAPP_URL must be an https URL");
            }
        }
        if (bot.isActive() && !MiniAppUrl.usable(bot.getMiniappUrl())) {
            log.warn("[telegram] MINIAPP_URL is missing or not https: "
                    + "the bot sends its messages without the open button and leaves the menu button as it is");
        }
    }
}
