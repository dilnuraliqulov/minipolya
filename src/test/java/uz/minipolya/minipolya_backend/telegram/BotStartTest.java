package uz.minipolya.minipolya_backend.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.service.AuthService;
import uz.minipolya.minipolya_backend.service.UserService;
import uz.minipolya.minipolya_backend.util.TelegramUserData;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /start: with and without a phone, a deep link, a blocked user. Telegram API mocked. */
class BotStartTest {

    private static final long TG = 5001L;

    private final BotFixture bot = new BotFixture();
    private final AuthService authService = mock(AuthService.class);
    private TelegramBotFlow flow;
    private User user;

    @BeforeEach
    void setUp() {
        user = User.builder().id(UUID.randomUUID()).telegramId(TG).name("Ali Valiyev").language("uz").build();
        when(authService.findOrCreateFromTelegram(any(TelegramUserData.class))).thenReturn(user);
        flow = bot.flow(authService, mock(UserService.class));
    }

    @Test
    void withoutAPhoneTheBannerOpensTheMiniAppAndASecondMessageAsksForIt() {
        flow.onMessage(BotFixture.message(TG, "Ali", "/start"));

        verify(authService).findOrCreateFromTelegram(argThat(data -> data.telegramId() == TG
                && "Ali".equals(data.firstName())));
        assertThat(bot.sent()).hasSize(2);
        BotFixture.Call banner = bot.sent().getFirst();
        assertThat(banner.method()).isEqualTo("sendPhoto");
        assertThat(banner.text()).isEqualTo("<b>Assalomu alaykum, Ali!</b> 👋\n\n"
                + "<b>MiniPolya</b> — maydon band qilishning eng oson yoʻli.");
        assertThat(banner.params()).containsEntry("parse_mode", "HTML");
        assertThat(banner.webAppUrl()).isEqualTo(BotFixture.MINIAPP);

        BotFixture.Call call = bot.last();
        assertThat(call.method()).isEqualTo("sendMessage");
        assertThat(call.text()).isEqualTo(
                "Bronlarni tasdiqlash va maydon egasi siz bilan bogʻlanishi uchun telefon raqamingizni yuboring 👇");
        assertThat(call.markup()).containsEntry("resize_keyboard", true)
                .containsEntry("one_time_keyboard", true)
                .containsEntry("input_field_placeholder", "Pastdagi tugmani bosing")
                .containsEntry("keyboard", List.of(List.of(
                        java.util.Map.of("text", "📱 Telefon raqamni yuborish", "request_contact", true))));
    }

    @Test
    void withoutAPhoneADeepLinkIsOnTheBannerButton() {
        flow.onMessage(BotFixture.message(TG, "Ali", "/start game_42"));

        assertThat(bot.sent().getFirst().webAppUrl()).isEqualTo(BotFixture.MINIAPP + "?startapp=game_42");
    }

    @Test
    void withoutAMiniAppUrlTheTextsGoOutWithNoButton() {
        bot.properties.getBot().setMiniappUrl("");

        flow.onMessage(BotFixture.message(TG, "Ali", "/start"));

        assertThat(bot.sent().getFirst().markup()).isNull();
        assertThat(bot.last().asksForContact()).isTrue();
    }

    @Test
    void aMiniAppUrlThatIsNotHttpsGivesNoButtonEither() {
        bot.properties.getBot().setMiniappUrl("http://localhost:5173");
        user.setPhone("+998901234558");

        flow.onMessage(BotFixture.message(TG, "Ali", "/start"));

        assertThat(bot.sent()).hasSize(1);
        assertThat(bot.last().text()).startsWith("<b>Xush kelibsiz, Ali!</b>");
        assertThat(bot.last().markup()).isNull();
    }

    @Test
    void withAPhoneTheBannerWelcomesAndOpensTheMiniApp() {
        user.setPhone("+998901234558");

        flow.onMessage(BotFixture.message(TG, "Ali", "/start"));

        assertThat(bot.sent()).hasSize(1);
        BotFixture.Call call = bot.last();
        assertThat(call.method()).isEqualTo("sendPhoto");
        assertThat(call.text()).isEqualTo("<b>Xush kelibsiz, Ali!</b> ⚽\n\nMaydon tanlang va bir necha soniyada band qiling.");
        assertThat(call.markup().toString()).contains("⚽ MiniPolya'ni ochish");
        assertThat(call.webAppUrl()).isEqualTo(BotFixture.MINIAPP);
    }

    @Test
    void theBannerIsUploadedOnceThenSentByItsFileId() {
        user.setPhone("+998901234558");

        flow.onMessage(BotFixture.message(TG, "Ali", "/start"));
        flow.onMessage(BotFixture.message(TG, "Ali", "/start"));

        verify(bot.api).upload(eq("sendPhoto"), any(), eq("photo"), any(byte[].class), eq("banner.png"));
        BotFixture.Call second = bot.last();
        assertThat(second.params().get("photo")).isEqualTo("BANNER-FILE-ID");
    }

    @Test
    void aDeepLinkIsPassedToTheMiniApp() {
        user.setPhone("+998901234558");

        flow.onMessage(BotFixture.message(TG, "Ali", "/start game_0193a6b2-7c1d-7e2f-9a00-1234567890ab"));

        assertThat(bot.last().webAppUrl())
                .isEqualTo(BotFixture.MINIAPP + "?startapp=game_0193a6b2-7c1d-7e2f-9a00-1234567890ab");
    }

    @Test
    void anInvalidDeepLinkIsDropped() {
        user.setPhone("+998901234558");

        flow.onMessage(BotFixture.message(TG, "Ali", "/start x\"><script>"));

        assertThat(bot.last().webAppUrl()).isEqualTo(BotFixture.MINIAPP);
    }

    @Test
    void theFirstNameIsEscapedForHtml() {
        flow.onMessage(BotFixture.message(TG, "<b>Ali</b> & Co", "/start"));

        assertThat(bot.sent().getFirst().text()).startsWith("<b>Assalomu alaykum, &lt;b&gt;Ali&lt;/b&gt; &amp; Co!</b>");
    }

    @Test
    void theUsersSavedLanguageIsUsed() {
        user.setLanguage("ru");

        flow.onMessage(BotFixture.message(TG, "Ali", "/start"));

        assertThat(bot.sent().getFirst().text()).startsWith("<b>Здравствуйте, Ali!</b>");
        assertThat(bot.last().markup().toString()).contains("📱 Отправить номер телефона");
    }

    @Test
    void aBlockedUserGetsOnlyTheBlockedMessageAndSupport() {
        user.setBlocked(true);
        bot.properties.getBot().setSupportUsername("@minipolya_support");

        flow.onMessage(BotFixture.message(TG, "Ali", "/start"));

        assertThat(bot.sent()).hasSize(1);
        BotFixture.Call call = bot.last();
        assertThat(call.method()).isEqualTo("sendMessage");
        assertThat(call.text()).isEqualTo("Hisobingiz bloklangan.\n\nYordam uchun: @minipolya_support");
        verify(bot.api, never()).upload(any(), any(), any(), any(), any());
    }

    @Test
    void withoutABannerFileIdTheTextStillGoesOutWhenTheUploadIsRefused() {
        when(bot.api.upload(eq("sendPhoto"), any(), eq("photo"), any(byte[].class), any()))
                .thenThrow(new TelegramApiException("sendPhoto", 400, "Bad Request: IMAGE_PROCESS_FAILED"));

        flow.onMessage(BotFixture.message(TG, "Ali", "/start"));

        assertThat(bot.last().method()).isEqualTo("sendMessage");
        assertThat(bot.last().asksForContact()).isTrue();
    }

    @Test
    void commandsAreRecognisedWithTheBotNameAndAPayload() {
        assertThat(TelegramBotFlow.command("/start@MiniPolyaBot game_1")).isEqualTo("start");
        assertThat(TelegramBotFlow.argument("/start@MiniPolyaBot game_1")).isEqualTo("game_1");
        assertThat(TelegramBotFlow.command("hello")).isNull();
        assertThat(TelegramBotFlow.argument("/start")).isNull();
    }
}
