package uz.minipolya.minipolya_backend.telegram;

import java.net.URI;
import java.net.URISyntaxException;

/** Telegram refuses a web_app button whose URL is not https. */
final class MiniAppUrl {

    private MiniAppUrl() {
    }

    static boolean usable(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(url.trim());
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (URISyntaxException ex) {
            return false;
        }
    }
}
