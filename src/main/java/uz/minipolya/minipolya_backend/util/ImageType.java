package uz.minipolya.minipolya_backend.util;

import java.util.Arrays;
import java.util.Optional;

/**
 * The image formats a stadium photo may be, told apart by their first bytes (magic numbers),
 * never by the client's content type or file name.
 */
public enum ImageType {
    JPEG("jpg", "image/jpeg"),
    PNG("png", "image/png"),
    WEBP("webp", "image/webp");

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] RIFF = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MARK = {'W', 'E', 'B', 'P'};

    private final String extension;
    private final String contentType;

    ImageType(String extension, String contentType) {
        this.extension = extension;
        this.contentType = contentType;
    }

    public String extension() {
        return extension;
    }

    public String contentType() {
        return contentType;
    }

    /** The format these bytes start with, or empty when they are none of the three. */
    public static Optional<ImageType> detect(byte[] bytes) {
        if (bytes == null) {
            return Optional.empty();
        }
        if (startsWith(bytes, 0, JPEG_MAGIC)) {
            return Optional.of(JPEG);
        }
        if (startsWith(bytes, 0, PNG_MAGIC)) {
            return Optional.of(PNG);
        }
        // RIFF <4-byte size> WEBP
        if (startsWith(bytes, 0, RIFF) && startsWith(bytes, 8, WEBP_MARK)) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /** The format a stored file name's extension stands for. */
    public static Optional<ImageType> fromExtension(String extension) {
        return Arrays.stream(values()).filter(t -> t.extension.equals(extension)).findFirst();
    }

    private static boolean startsWith(byte[] bytes, int offset, byte[] prefix) {
        if (bytes.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }
}
