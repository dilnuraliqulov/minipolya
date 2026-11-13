package uz.minipolya.minipolya_backend.telegram;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;
import uz.minipolya.minipolya_backend.config.AppProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(OutputCaptureExtension.class)
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
    void pollingRunsUnderDevAndStaging() {
        assertThatCode(() -> TelegramBotModeGuard.check(bot("polling"), env("dev"))).doesNotThrowAnyException();
        assertThatCode(() -> TelegramBotModeGuard.check(bot("polling"), env("staging"))).doesNotThrowAnyException();
    }

    @Test
    void pollingRefusesToStartUnderProd() {
        assertThatThrownBy(() -> TelegramBotModeGuard.check(bot("polling"), env("prod")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("dev or staging profile");
        assertThatThrownBy(() -> TelegramBotModeGuard.check(bot("polling"), env()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stagingPollingNeedsAnHttpsMiniAppButNoWebhookSecret() {
        AppProperties.Bot polling = bot("polling");
        polling.setWebhookSecret(""); // nothing registers a webhook in polling mode
        assertThatCode(() -> TelegramBotModeGuard.check(polling, env("staging"))).doesNotThrowAnyException();

        AppProperties.Bot http = bot("polling");
        http.setMiniappUrl("http://app.example");
        assertThatThrownBy(() -> TelegramBotModeGuard.check(http, env("staging")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MINIAPP_URL");
    }

    @Test
    void stagingInWebhookModeStillNeedsTheSecret() {
        AppProperties.Bot noSecret = bot("webhook");
        noSecret.setWebhookSecret("too-short");
        assertThatThrownBy(() -> TelegramBotModeGuard.check(noSecret, env("staging")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("TELEGRAM_WEBHOOK_SECRET");
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
    void outsideProdAMiniAppUrlThatIsNotHttpsIsAWarningAtStartup(CapturedOutput output) {
        AppProperties.Bot http = bot("polling");
        http.setMiniappUrl("http://localhost:5173");

        assertThatCode(() -> TelegramBotModeGuard.check(http, env("dev"))).doesNotThrowAnyException();

        assertThat(output.getOut()).contains("MINIAPP_URL").contains("https");
    }

    @Test
    void aGoodMiniAppUrlIsNotWarnedAbout(CapturedOutput output) {
        TelegramBotModeGuard.check(bot("polling"), env("dev"));

        assertThat(output.getOut()).doesNotContain("MINIAPP_URL");
    }

    @Test
    void outsideProdAMissingSecretOnlyMeansEveryWebhookIsRefused() {
        AppProperties.Bot noSecret = bot("webhook");
        noSecret.setWebhookSecret("");
        assertThatCode(() -> TelegramBotModeGuard.check(noSecret, env())).doesNotThrowAnyException();
    }
}
