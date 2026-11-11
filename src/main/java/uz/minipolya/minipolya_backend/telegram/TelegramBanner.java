package uz.minipolya.minipolya_backend.telegram;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import uz.minipolya.minipolya_backend.entity.TelegramBotValue;
import uz.minipolya.minipolya_backend.repository.TelegramBotValueRepository;
import uz.minipolya.minipolya_backend.service.TelegramBotService;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sends the /start banner ({@code telegram/banner.png}) with a caption. The first send
 * uploads the file; Telegram's file_id for it is then kept in {@code telegram_bot_values}
 * and reused, so the image is uploaded once. The cached id is tied to the image's SHA-256:
 * replace the PNG and the next send uploads the new one. Without the image, or if Telegram
 * refuses it, the caption goes out as a plain message.
 */
@Slf4j
@Component
public class TelegramBanner {

    static final String RESOURCE = "telegram/banner.png";
    static final String FILE_ID_KEY = "banner_file_id";

    private final TelegramApi api;
    private final TelegramBotService botService;
    private final TelegramBotValueRepository values;
    private final byte[] image;
    private final String imageHash;

    public TelegramBanner(TelegramApi api, TelegramBotService botService, TelegramBotValueRepository values) {
        this.api = api;
        this.botService = botService;
        this.values = values;
        this.image = read();
        this.imageHash = image == null ? null : sha256(image);
    }

    public void send(long chatId, String captionHtml, Object replyMarkup) {
        if (image == null) {
            botService.send(chatId, captionHtml, replyMarkup);
            return;
        }
        String cached = cachedFileId();
        if (cached != null) {
            try {
                api.call("sendPhoto", params(chatId, cached, captionHtml, replyMarkup));
                return;
            } catch (TelegramApiException ex) {
                if (ex.getErrorCode() != 400) {
                    throw ex;
                }
                // A file_id from another bot token, or one Telegram no longer knows: upload again.
                log.warn("[telegram] cached banner file_id refused, uploading again");
                values.deleteById(FILE_ID_KEY);
            }
        }
        try {
            Map<String, Object> params = params(chatId, null, captionHtml, replyMarkup);
            JsonNode sent = api.upload("sendPhoto", params, "photo", image, "banner.png");
            remember(sent);
        } catch (TelegramApiException ex) {
            if (ex.getErrorCode() != 400) {
                throw ex;
            }
            log.warn("[telegram] banner upload refused ({}), sending the text alone", ex.getMessage());
            botService.send(chatId, captionHtml, replyMarkup);
        }
    }

    private Map<String, Object> params(long chatId, String fileId, String captionHtml, Object replyMarkup) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("chat_id", chatId);
        if (fileId != null) {
            params.put("photo", fileId);
        }
        params.put("caption", captionHtml);
        params.put("parse_mode", "HTML");
        if (replyMarkup != null) {
            params.put("reply_markup", replyMarkup);
        }
        return params;
    }

    /** The cached file_id, if it was made from this very image. Stored as "sha256:file_id". */
    private String cachedFileId() {
        return values.findById(FILE_ID_KEY)
                .map(TelegramBotValue::getValue)
                .filter(value -> value.startsWith(imageHash + ":"))
                .map(value -> value.substring(imageHash.length() + 1))
                .orElse(null);
    }

    /** Keeps the largest size's file_id of the photo Telegram just stored. */
    private void remember(JsonNode sentMessage) {
        JsonNode sizes = sentMessage.path("photo");
        if (!sizes.isArray() || sizes.isEmpty()) {
            return;
        }
        String fileId = sizes.get(sizes.size() - 1).path("file_id").asString("");
        if (!fileId.isEmpty()) {
            values.save(new TelegramBotValue(FILE_ID_KEY, imageHash + ":" + fileId));
        }
    }

    private static byte[] read() {
        try (InputStream in = TelegramBanner.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException ex) {
            return null;
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
