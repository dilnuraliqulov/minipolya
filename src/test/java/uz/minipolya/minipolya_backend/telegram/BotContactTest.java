package uz.minipolya.minipolya_backend.telegram;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.entity.AuditLog;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.AuditLogRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.service.AuthService;
import uz.minipolya.minipolya_backend.service.UserService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The shared contact, through the real AuthService and UserService and the database; only
 * the Telegram API is mocked. Also: a brand-new user from /start, and text before the phone.
 */
@DatabaseTest
class BotContactTest {

    private static final AtomicLong TELEGRAM_IDS = new AtomicLong(880_000_000L + (System.nanoTime() % 1_000_000));

    @Autowired
    private AuthService authService;
    @Autowired
    private UserService userService;
    @Autowired
    private UserRepository users;
    @Autowired
    private AuditLogRepository auditLog;
    @Autowired
    private TestData data;

    private final BotFixture bot = new BotFixture();
    private TelegramBotFlow flow;
    private long tg;

    @BeforeEach
    void setUp() {
        flow = bot.flow(authService, userService);
        tg = TELEGRAM_IDS.incrementAndGet();
    }

    private User user() {
        return users.findByTelegramId(tg).orElseThrow();
    }

    @Test
    void startCreatesTheUserInUzbekWithTheSameCodeAsTheMiniApp() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "/start"));

        User created = user();
        assertThat(created.getName()).isEqualTo("Aziz");
        assertThat(created.getLanguage()).isEqualTo("uz");
        assertThat(created.getPhone()).isNull();
        assertThat(bot.last().asksForContact()).isTrue();
    }

    @Test
    void theOwnContactIsSavedConfirmedMaskedAndFollowedByTheOpenButton() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "/start"));
        String phone = uniqueUzPhone();

        flow.onMessage(BotFixture.contact(tg, tg, phone.substring(1)));   // Telegram often omits the +

        assertThat(user().getPhone()).isEqualTo(phone);
        List<BotFixture.Call> sent = bot.sent();
        BotFixture.Call saved = sent.get(sent.size() - 2);
        BotFixture.Call next = sent.getLast();
        String masked = "+998 " + phone.substring(4, 6) + " *** ** " + phone.substring(11);
        assertThat(saved.text()).isEqualTo("✅ Rahmat! Raqamingiz saqlandi: " + masked);
        assertThat(saved.removesKeyboard()).isTrue();
        assertThat(next.text()).isEqualTo("Endi maydonni tanlang va band qiling 👇");
        assertThat(next.webAppUrl()).isEqualTo(BotFixture.MINIAPP);

        AuditLog entry = auditLog.findFirstByEntityTypeAndEntityIdAndActionOrderByCreatedAtDescIdDesc(
                AuditEntityType.USER, user().getId(), AuditAction.PHONE_SHARED_VIA_BOT.name()).orElseThrow();
        assertThat(entry.getActorType()).isEqualTo("PLAYER");
        assertThat(entry.getChanges()).contains("phoneChanged").doesNotContain(phone.substring(4));
    }

    @Test
    void aDeepLinkFromBeforeThePhoneReachesTheOpenButtonAfterIt() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "/start game_abc-123"));
        flow.onMessage(BotFixture.contact(tg, tg, uniqueUzPhone()));

        assertThat(bot.last().webAppUrl()).isEqualTo(BotFixture.MINIAPP + "?startapp=game_abc-123");
    }

    @Test
    void someoneElsesContactIsRefusedAndTheButtonKept() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "/start"));

        flow.onMessage(BotFixture.contact(tg, tg + 1, uniqueUzPhone()));

        assertThat(user().getPhone()).isNull();
        assertThat(bot.last().text()).isEqualTo("Iltimos, oʻzingizning raqamingizni yuboring — pastdagi tugmani bosing.");
        assertThat(bot.last().asksForContact()).isTrue();
    }

    @Test
    void aContactWithoutAUserIdIsRefusedToo() {
        flow.onMessage(BotFixture.contact(tg, null, uniqueUzPhone()));

        assertThat(user().getPhone()).isNull();
        assertThat(bot.last().asksForContact()).isTrue();
    }

    @Test
    void aNonUzbekNumberIsRefused() {
        flow.onMessage(BotFixture.contact(tg, tg, "+79123456789"));

        assertThat(user().getPhone()).isNull();
        assertThat(bot.last().text()).isEqualTo("Hozircha faqat Oʻzbekiston raqamlari qabul qilinadi.");
    }

    @Test
    void aPhoneAnotherAccountHasIsNeitherOverwrittenNorMerged() {
        User other = data.user("Other Player");
        String takenPhone = "+" + other.getPhone();
        other.setPhone(takenPhone);
        users.saveAndFlush(other);
        bot.properties.getBot().setSupportUsername("minipolya_support");

        flow.onMessage(BotFixture.contact(tg, tg, takenPhone));

        assertThat(user().getPhone()).isNull();
        assertThat(users.findById(other.getId()).orElseThrow().getTelegramId()).isNull();
        assertThat(bot.last().text()).isEqualTo(
                "Bu raqam boshqa hisobga biriktirilgan.\n\nYordam uchun: @minipolya_support");
    }

    @Test
    void anyOtherTextBeforeThePhoneAsksAgainWithTheButton() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "salom"));

        assertThat(bot.last().text()).isEqualTo("Davom etish uchun telefon raqamingizni yuboring — pastdagi tugmani bosing 👇");
        assertThat(bot.last().asksForContact()).isTrue();
    }

    @Test
    void aBlockedUserCannotShareAPhone() {
        flow.onMessage(BotFixture.message(tg, "Aziz", "/start"));
        User user = user();
        user.setBlocked(true);
        users.saveAndFlush(user);

        flow.onMessage(BotFixture.contact(tg, tg, uniqueUzPhone()));

        assertThat(user().getPhone()).isNull();
        assertThat(bot.last().text()).isEqualTo("Hisobingiz bloklangan.");
    }

    @Test
    void uzbekNumbersAreNormalisedHoweverTheyAreWritten() {
        assertThat(TelegramBotFlow.uzbekPhone("998901234558")).contains("+998901234558");
        assertThat(TelegramBotFlow.uzbekPhone("+998 90 123-45-58")).contains("+998901234558");
        assertThat(TelegramBotFlow.uzbekPhone("+7 912 345 67 89")).isEmpty();
        assertThat(TelegramBotFlow.uzbekPhone("90123455")).isEmpty();
    }

    /** A +998 number no other test row uses. */
    private String uniqueUzPhone() {
        return String.format("+99877%07d", tg % 10_000_000);
    }
}
