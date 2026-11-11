package uz.minipolya.minipolya_backend.util;

import uz.minipolya.minipolya_backend.exception.BadRequestException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Iterator;

/**
 * Turns an uploaded photo (JPEG, PNG or WebP, told by its magic bytes) into a plain JPEG
 * (design D5): the size is read from the header first and anything over
 * {@value #MAX_PIXELS} pixels is refused before decoding (a decompression bomb); the image
 * is decoded, transparency flattened onto white, scaled so the long side is at most
 * {@value #MAX_SIDE} px, and written at quality {@value #QUALITY} with no metadata — so
 * EXIF (GPS, camera) never reaches the disk. EXIF orientation is not applied: the Mini App
 * bakes it in before uploading.
 */
public final class ImageReencoder {

    public static final long MAX_PIXELS = 40_000_000L;
    public static final int MAX_SIDE = 2000;
    public static final float QUALITY = 0.85f;

    private static final String UNSUPPORTED = "Unsupported image type";

    static {
        // Under the packaged jar ImageIO's registry may be built before the WebP plugin's
        // jar is visible to it; scan again with this class's loader.
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(ImageReencoder.class.getClassLoader());
            ImageIO.scanForPlugins();
        } finally {
            thread.setContextClassLoader(previous);
        }
        ImageIO.setUseCache(false); // in memory, no temp files
    }

    private ImageReencoder() {
    }

    /** The photo as a JPEG; 400 "Unsupported image type" or "Image is too large" otherwise. */
    public static byte[] toJpeg(byte[] bytes) {
        ImageType type = ImageType.detect(bytes).orElseThrow(() -> new BadRequestException(UNSUPPORTED));
        BufferedImage decoded = decode(bytes, type);
        BufferedImage scaled = scaleOntoWhite(decoded);
        return writeJpeg(scaled);
    }

    private static BufferedImage decode(byte[] bytes, ImageType type) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType(type.contentType());
            if (in == null || !readers.hasNext()) {
                throw new BadRequestException(UNSUPPORTED);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true); // one image, metadata ignored
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new BadRequestException(UNSUPPORTED);
                }
                if (width * height > MAX_PIXELS) {
                    throw new BadRequestException("Image is too large");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                // A very large photo is read every n-th pixel (still at least twice the target),
                // so decoding does not hold the full-size image in memory.
                int step = (int) Math.max(1, Math.max(width, height) / (2L * MAX_SIDE));
                if (step > 1) {
                    param.setSourceSubsampling(step, step, 0, 0);
                }
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw new BadRequestException(UNSUPPORTED);
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (BadRequestException ex) {
            throw ex;
        } catch (IOException | RuntimeException ex) {
            // A truncated or corrupt file, or one the decoder cannot handle (e.g. a CMYK JPEG).
            throw new BadRequestException(UNSUPPORTED);
        }
    }

    /**
     * Opaque RGB at most {@link #MAX_SIDE} on the long side: drawn onto white (transparent
     * pixels become white, not black), then halved step by step with bilinear filtering and
     * finished with one bicubic step, which keeps a large downscale sharp without aliasing.
     */
    private static BufferedImage scaleOntoWhite(BufferedImage source) {
        int width = source.getWidth();
        int height = source.getHeight();
        double factor = Math.min(1.0, (double) MAX_SIDE / Math.max(width, height));
        int targetWidth = Math.max(1, (int) Math.round(width * factor));
        int targetHeight = Math.max(1, (int) Math.round(height * factor));

        BufferedImage current = draw(source, width, height, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        while (current.getWidth() / 2 >= targetWidth && current.getHeight() / 2 >= targetHeight) {
            current = draw(current, current.getWidth() / 2, current.getHeight() / 2,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (current.getWidth() != targetWidth || current.getHeight() != targetHeight) {
            current = draw(current, targetWidth, targetHeight, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        return current;
    }

    private static BufferedImage draw(BufferedImage source, int width, int height, Object interpolation) {
        BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = target.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(source, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return target;
    }

    private static byte[] writeJpeg(BufferedImage image) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IllegalStateException("No JPEG writer");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(QUALITY);
            // No metadata passed: only the writer's own JFIF header, never EXIF.
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not encode the photo", ex);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
