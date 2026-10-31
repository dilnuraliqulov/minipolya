package uz.minipolya.minipolya_backend.telegram;

/** Text placed inside a Telegram HTML message: only &amp;, &lt; and &gt; need escaping. */
public final class TelegramHtml {

    private TelegramHtml() {
    }

    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
