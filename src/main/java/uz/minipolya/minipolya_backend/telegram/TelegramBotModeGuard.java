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
 *   <li>{@code polling} outside the {@code dev} and {@code staging} profiles (so never in
 *       prod). Staging is a test deployment with no public webhook URL, so it polls — with a
 *       TEST bot: polling deletes that bot's webhook, and two processes must never poll the
 *       same bot;</li>
 *   <li>on a deployed server ({@code prod} or {@code staging}) with the bot active: a
 *       MINIAPP_URL that is not https (Telegram refuses web_app buttons without it), and, in
 *       webhook mode, a TELEGRAM_WEBHOOK_SECRET shorter than 32 characters or with characters
 *       Telegram does not allow. Under {@code prod} the mode can only be webhook, so this is
 *       the same pair of refusals prod has always had.</li>
 * </ul>
 * On a developer's machine an unusable MINIAPP_URL is only a warning: the bot still talks,
 * without the open button.
 */
@Slf4j
@Configuration
public class TelegramBotModeGuard {

    /** Telegram's secret_token alphabet; 32 characters is our own floor. */
    static final Pattern SECRET = Pattern.compile("^[A-Za-z0-9_-]{32,256}$");

    /** Profiles where a getUpdates loop is a working setup rather than a production mistake. */
    private static final Profiles POLLING_ALLOWED = Profiles.of("dev | staging");

    /** Profiles where the bot talks to real Telegram users, so its URL and secret must hold up. */
    private static final Profiles DEPLOYED = Profiles.of("prod | staging");

    public TelegramBotModeGuard(AppProperties appProperties, Environment environment) {
        check(appProperties.getBot(), environment);
    }

    static void check(AppProperties.Bot bot, Environment environment) {
        String mode = bot.getMode() == null ? "" : bot.getMode().trim().toLowerCase();
        if (!mode.equals("webhook") && !mode.equals("polling")) {
            throw new IllegalStateException("TELEGRAM_BOT_MODE must be webhook or polling");
        }
        if (bot.isPolling() && !environment.acceptsProfiles(POLLING_ALLOWED)) {
            throw new IllegalStateException(
                    "TELEGRAM_BOT_MODE=polling is for development and the staging test deployment: "
                            + "it needs the dev or staging profile");
        }
        if (bot.isActive() && environment.acceptsProfiles(DEPLOYED)) {
            // Only webhook mode is told a secret, and prod can only be in webhook mode.
            String secret = bot.getWebhookSecret();
            if (!bot.isPolling() && (secret == null || !SECRET.matcher(secret).matches())) {
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
