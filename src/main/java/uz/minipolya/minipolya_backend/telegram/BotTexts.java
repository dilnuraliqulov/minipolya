package uz.minipolya.minipolya_backend.telegram;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Every text the bot shows, one bundle per language: {@code telegram/bot_uz.properties}
 * (the default), {@code bot_ru} and {@code bot_en}, read as UTF-8. Placeholders are
 * {@code {name}}, replaced literally: no MessageFormat, so an apostrophe ("MiniPolya'ni")
 * stays as written. A key missing in ru or en falls back to uz.
 */
@Component
public class BotTexts {

    /** The languages a user can pick, in the order /language shows them; {@code user.language} values. */
    public static final List<String> LANGUAGES = List.of("uz", "ru", "en");
    public static final String DEFAULT_LANGUAGE = "uz";

    private final Map<String, Properties> bundles = new LinkedHashMap<>();

    public BotTexts() {
        for (String language : LANGUAGES) {
            bundles.put(language, load("telegram/bot_" + language + ".properties"));
        }
    }

    /** The user's saved language when it is one of ours, else uz. */
    public static String language(String saved) {
        return saved != null && LANGUAGES.contains(saved) ? saved : DEFAULT_LANGUAGE;
    }

    /**
     * The text for {@code key} in {@code language}, with each {@code {name}} replaced:
     * {@code values} alternates name and value. Values are inserted as given; escape them
     * for HTML before passing them in.
     */
    public String get(String language, String key, String... values) {
        String text = bundles.get(language(language)).getProperty(key);
        if (text == null) {
            text = bundles.get(DEFAULT_LANGUAGE).getProperty(key);
        }
        if (text == null) {
            throw new IllegalStateException("No bot text for key " + key);
        }
        for (int i = 0; i + 1 < values.length; i += 2) {
            text = text.replace("{" + values[i] + "}", values[i + 1] == null ? "" : values[i + 1]);
        }
        return text;
    }

    /** Every key of a language's own bundle (for the test that all three have the same). */
    java.util.Set<String> keys(String language) {
        return bundles.get(language).stringPropertyNames();
    }

    private static Properties load(String path) {
        Properties properties = new Properties();
        try (InputStream in = BotTexts.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing bot texts: " + path);
            }
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return properties;
    }
}
