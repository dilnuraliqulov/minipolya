package uz.minipolya.minipolya_backend.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uz.minipolya.minipolya_backend.config.AppProperties;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TelegramBotModeGuardTest {

    private static final String GOOD_SECRET = "abcdefghijklmnopqrstuvwxyz_0123456789-ABC";

    private static MockEnvironment env(String... profiles) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profiles);
        return environment;
    }

    private static AppProperties.Bot bot(String mode) {
        AppProperties.Bot bot = new AppProperties.Bot();
        bot.setToken("123:abc");
        bot.setMode(mode);
        bot.setWebhookSecret(GOOD_SECRET);
        bot.setMiniappUrl("https://app.minipolya.uz");
        return bot;
    }

    @Test
    void pollingRunsUnderDev() {
        assertThatCode(() -> TelegramBotModeGuard.check(bot("polling"), env("dev"))).doesNotThrowAnyException();
    }

    @Test
    void pollingRefusesToStartOutsideDev() {
        assertThatThrownBy(() -> TelegramBotModeGuard.check(bot("polling"), env("prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dev profile");
        assertThatThrownBy(() -> TelegramBotModeGuard.check(bot("polling"), env()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void anUnknownModeRefusesToStart() {
        assertThatThrownBy(() -> TelegramBotModeGuard.check(bot("both"), env("dev")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("webhook or polling");
    }

    @Test
    void prodNeedsAStrongSecretAndAnHttpsMiniApp() {
        assertThatCode(() -> TelegramBotModeGuard.check(bot("webhook"), env("prod"))).doesNotThrowAnyException();

        AppProperties.Bot shortSecret = bot("webhook");
        shortSecret.setWebhookSecret("too-short");
        assertThatThrownBy(() -> TelegramBotModeGuard.check(shortSecret, env("prod")))
                .hasMessageContaining("TELEGRAM_WEBHOOK_SECRET");

        AppProperties.Bot http = bot("webhook");
        http.setMiniappUrl("http://app.minipolya.uz");
        assertThatThrownBy(() -> TelegramBotModeGuard.check(http, env("prod")))
                .hasMessageContaining("MINIAPP_URL");
    }

    @Test
    void outsideProdAMissingSecretOnlyMeansEveryWebhookIsRefused() {
        AppProperties.Bot noSecret = bot("webhook");
        noSecret.setWebhookSecret("");
        assertThatCode(() -> TelegramBotModeGuard.check(noSecret, env())).doesNotThrowAnyException();
    }
}
