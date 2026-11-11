package uz.minipolya.minipolya_backend.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.service.AuthService;
import uz.minipolya.minipolya_backend.service.UserService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** /help and /language, through the real services and database; the Telegram API mocked. */
@DatabaseTest
class BotHelpAndLanguageTest {

    private static final AtomicLong TELEGRAM_IDS = new AtomicLong(770_000_000L + (System.nanoTime() % 1_000_000));

    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository users;

    private final BotFixture bot = new BotFixture();
    private TelegramBotFlow flow;
    private long tg;

    @BeforeEach
    void setUp() {
        flow = bot.flow(authService, userService);
        tg = TELEGRAM_IDS.incrementAndGet();
    }

    @Test
    void helpExplainsTheThreeStepsTheCancellationRuleAndSupport() {
        bot.properties.getBot().setSupportUsername("minipolya_support");

        flow.onMessage(BotFixture.message(tg, "Aziz", "/help"));

        BotFixture.Call help = bot.last();
        assertThat(help.text())
                .contains("1️⃣ Maydonni tanlang", "2️⃣ Boʻsh vaqtni tanlang", "3️⃣ Uzcard yoki Humo karta bilan toʻlang")
                .contains("24 soat")
                .endsWith("\n\nYordam uchun: @minipolya_support");
        assertThat(help.webAppUrl()).isEqualTo(BotFixture.MINIAPP);
    }

    @Test
    void helpWithoutASupportContactLeavesTheLineOut() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "/help"));

        assertThat(bot.last().text()).doesNotContain("@");
    }

    @Test
    void languageOffersTheThreeLanguages() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "/language"));

        BotFixture.Call call = bot.last();
        assertThat(call.text()).isEqualTo("🌐 Tilni tanlang:");
        assertThat(call.markup()).isEqualTo(Map.of("inline_keyboard", List.of(List.of(
                Map.of("text", "🇺🇿 Oʻzbekcha", "callback_data", "lang:uz"),
                Map.of("text", "🇷🇺 Русский", "callback_data", "lang:ru"),
                Map.of("text", "🇬🇧 English", "callback_data", "lang:en")))));
    }

    @Test
    void choosingALanguageSavesItEditsTheMessageAndIsUsedFromThenOn() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "/language"));

        flow.onCallbackQuery(BotFixture.callback(tg, 555, "lang:ru"));

        User user = users.findByTelegramId(tg).orElseThrow();
        assertThat(user.getLanguage()).isEqualTo("ru");
        List<BotFixture.Call> sent = bot.sent();
        assertThat(sent.stream().map(BotFixture.Call::method)).contains("answerCallbackQuery");
        BotFixture.Call edit = sent.getLast();
        assertThat(edit.method()).isEqualTo("editMessageText");
        assertThat(edit.params()).containsEntry("message_id", 555L).containsEntry("chat_id", tg);
        assertThat(edit.text()).isEqualTo("✅ Язык изменён: Русский");

        flow.onMessage(BotFixture.message(tg, "Aziz", "/start"));
        assertThat(bot.sent().get(bot.sent().size() - 2).text()).startsWith("<b>Здравствуйте, Aziz!</b>");
        assertThat(bot.last().text()).startsWith("Отправьте свой номер телефона");

        flow.onMessage(BotFixture.message(tg, "Aziz", "/help"));
        assertThat(bot.last().text()).startsWith("<b>Как работает MiniPolya</b>");
    }

    @Test
    void anUnknownCallbackChangesNothing() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "/language"));

        flow.onCallbackQuery(BotFixture.callback(tg, 555, "lang:de"));
        flow.onCallbackQuery(BotFixture.callback(tg, 555, "something-else"));

        assertThat(users.findByTelegramId(tg).orElseThrow().getLanguage()).isEqualTo("uz");
        assertThat(bot.sent().stream().map(BotFixture.Call::method)).doesNotContain("editMessageText");
    }

    @Test
    void aBlockedUserCannotChangeTheLanguage() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "/language"));
        User user = users.findByTelegramId(tg).orElseThrow();
        user.setBlocked(true);
        users.saveAndFlush(user);

        flow.onCallbackQuery(BotFixture.callback(tg, 555, "lang:en"));

        assertThat(users.findByTelegramId(tg).orElseThrow().getLanguage()).isEqualTo("uz");
        assertThat(bot.last().text()).isEqualTo("Hisobingiz bloklangan.");
    }
}
