package uz.minipolya.minipolya_backend.telegram;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BotTextsTest {

    private final BotTexts texts = new BotTexts();

    @Test
    void everyLanguageHasExactlyTheSameKeys() {
        assertThat(texts.keys("ru")).isEqualTo(texts.keys("uz"));
        assertThat(texts.keys("en")).isEqualTo(texts.keys("uz"));
    }

    @Test
    void theUzbekProfileTextsAreTheAgreedOnes() {
        assertThat(texts.get("uz", "description")).isEqualTo(
                "⚽ MiniPolya — Toshkentdagi mini-futbol maydonlarini bir necha soniyada band qiling.\n\n"
                        + "✅ Boʻsh vaqtlarni koʻring va onlayn band qiling\n"
                        + "💳 Uzcard va Humo orqali xavfsiz toʻlov\n"
                        + "👥 Jamoangiz bilan raqib toping\n"
                        + "🔔 Oʻyin oldidan eslatma oling\n\n"
                        + "Boshlash uchun «Start» tugmasini bosing.");
        assertThat(texts.get("uz", "short_description")).isEqualTo(
                "Toshkentda mini-futbol maydonlarini onlayn band qilish. Jamoa, raqib va eslatmalar — bitta ilovada.");
    }

    @Test
    void everyTextFitsTelegramsLimits() {
        for (String language : BotTexts.LANGUAGES) {
            assertThat(texts.get(language, "description")).hasSizeLessThanOrEqualTo(512);
            assertThat(texts.get(language, "short_description")).hasSizeLessThanOrEqualTo(120);
            assertThat(texts.get(language, "placeholder.share_phone")).hasSizeLessThanOrEqualTo(64);
            for (String command : new String[] {"command.start", "command.help", "command.language"}) {
                assertThat(texts.get(language, command)).hasSizeBetween(1, 256);
            }
        }
    }

    @Test
    void placeholdersAreReplacedLiterally() {
        assertThat(texts.get("uz", "start.welcome", "firstName", "Ali"))
                .startsWith("<b>Xush kelibsiz, Ali!</b> ⚽");
        // An apostrophe survives (no MessageFormat).
        assertThat(texts.get("uz", "button.open")).isEqualTo("⚽ MiniPolya'ni ochish");
    }

    @Test
    void anUnknownLanguageFallsBackToUzbek() {
        assertThat(BotTexts.language("de")).isEqualTo("uz");
        assertThat(BotTexts.language(null)).isEqualTo("uz");
        assertThat(texts.get("de", "blocked")).isEqualTo("Hisobingiz bloklangan.");
    }
}
