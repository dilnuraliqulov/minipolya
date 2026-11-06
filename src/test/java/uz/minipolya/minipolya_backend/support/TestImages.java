package uz.minipolya.minipolya_backend.support;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * Real image bytes for the photo tests, made in memory with ImageIO. WebP cannot be written
 * by ImageIO, so the WebP samples are checked-in files under {@code src/test/resources/images}
 * (1×1 lossy, lossless and with alpha — the well-known WebP feature-detection images).
 */
public final class TestImages {

    private TestImages() {
    }

    public static byte[] jpeg(int width, int height) {
        return write(opaque(width, height), "jpeg");
    }

    public static byte[] png(int width, int height) {
        return write(opaque(width, height), "png");
    }

    /** Left half fully transparent, right half opaque red. */
    public static byte[] pngWithAlpha(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(0, 0, 0, 0));
        g.fillRect(0, 0, width / 2, height);
        g.setColor(Color.RED);
        g.fillRect(width / 2, 0, width - width / 2, height);
        g.dispose();
        return write(image, "png");
    }

    /**
     * A JPEG with an Exif APP1 segment right after SOI, carrying a fake camera model and
     * GPS-looking text — what a phone photo carries.
     */
    public static byte[] jpegWithExif(int width, int height) {
        byte[] jpeg = jpeg(width, height);
        byte[] payload = ("Exif\0\0" + "MM\0*CameraModel-SECRET GPS 41.2995N 69.2401E").getBytes(StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1); // APP1
        int length = payload.length + 2;
        out.write(length >> 8);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    /** Only a PNG signature and IHDR claiming {@code width}×{@code height}: no pixels to decode. */
    public static byte[] pngHeaderOnly(int width, int height) {
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    public static byte[] resource(String name) {
        try (InputStream in = TestImages.class.getResourceAsStream("/images/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No test image " + name);
            }
            return in.readAllBytes();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static BufferedImage read(byte[] bytes) {
        try {
            return ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    public static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }

    private static BufferedImage opaque(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(30, 140, 60));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        g.drawLine(0, height / 2, width, height / 2);
        g.dispose();
        return image;
    }

    private static byte[] write(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
