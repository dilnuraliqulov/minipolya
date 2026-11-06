package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.support.TestImages;

import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** D5: every stadium photo is decoded and written again as a plain JPEG. */
class ImageReencoderTest {

    private static void isJpeg(byte[] bytes) {
        assertThat(ImageType.detect(bytes)).contains(ImageType.JPEG);
    }

    @Test
    void aPngWithAlphaBecomesAJpegOnWhite() {
        byte[] out = ImageReencoder.toJpeg(TestImages.pngWithAlpha(40, 20));

        isJpeg(out);
        BufferedImage image = TestImages.read(out);
        assertThat(image.getWidth()).isEqualTo(40);
        assertThat(image.getHeight()).isEqualTo(20);
        int transparent = image.getRGB(5, 10);
        assertThat((transparent >> 16) & 0xFF).isGreaterThan(240); // white, not black
        assertThat((transparent >> 8) & 0xFF).isGreaterThan(240);
        assertThat(transparent & 0xFF).isGreaterThan(240);
        int red = image.getRGB(35, 10);
        assertThat((red >> 16) & 0xFF).isGreaterThan(200);
        assertThat((red >> 8) & 0xFF).isLessThan(60);
    }

    @Test
    void exifIsStripped() {
        byte[] in = TestImages.jpegWithExif(64, 48);
        assertThat(TestImages.contains(in, "Exif")).isTrue();
        assertThat(TestImages.contains(in, "SECRET")).isTrue();

        byte[] out = ImageReencoder.toJpeg(in);

        isJpeg(out);
        assertThat(TestImages.contains(out, "Exif")).isFalse();
        assertThat(TestImages.contains(out, "SECRET")).isFalse();
        // No APP1 segment at all.
        for (int i = 0; i + 1 < out.length; i++) {
            assertThat(out[i] == (byte) 0xFF && out[i + 1] == (byte) 0xE1).isFalse();
        }
    }

    @Test
    void theLongSideIsAtMost2000() {
        BufferedImage landscape = TestImages.read(ImageReencoder.toJpeg(TestImages.jpeg(4000, 3000)));
        assertThat(landscape.getWidth()).isEqualTo(2000);
        assertThat(landscape.getHeight()).isEqualTo(1500);

        BufferedImage portrait = TestImages.read(ImageReencoder.toJpeg(TestImages.png(1000, 3000)));
        assertThat(portrait.getWidth()).isEqualTo(667);
        assertThat(portrait.getHeight()).isEqualTo(2000);

        BufferedImage small = TestImages.read(ImageReencoder.toJpeg(TestImages.png(800, 600)));
        assertThat(small.getWidth()).isEqualTo(800);
        assertThat(small.getHeight()).isEqualTo(600);
    }

    @Test
    void webpIsDecoded() {
        for (String name : new String[]{"lossy-1x1.webp", "lossless-1x1.webp", "alpha-1x1.webp"}) {
            byte[] webp = TestImages.resource(name);
            assertThat(ImageType.detect(webp)).contains(ImageType.WEBP);

            byte[] out = ImageReencoder.toJpeg(webp);

            isJpeg(out);
            BufferedImage image = TestImages.read(out);
            assertThat(image.getWidth()).as(name).isEqualTo(1);
            assertThat(image.getHeight()).as(name).isEqualTo(1);
        }
    }

    @Test
    void garbageAfterTheMagicBytesIsAnUnsupportedImage() {
        byte[] jpegMagic = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 1, 2, 3};
        byte[] pngMagic = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 9, 9};
        byte[] webpMagic = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
        byte[] truncated = Arrays.copyOf(TestImages.png(50, 50), 60);
        for (byte[] bytes : new byte[][]{jpegMagic, pngMagic, webpMagic, truncated, "GIF89a".getBytes(), new byte[0]}) {
            assertThatThrownBy(() -> ImageReencoder.toJpeg(bytes))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessage("Unsupported image type");
        }
    }

    @Test
    void over40MegapixelsIsRefusedBeforeDecoding() {
        // 8000 × 5001 = 40 008 000 pixels: only the header exists, so a decode would fail
        // differently — the header alone must be enough to refuse it.
        assertThatThrownBy(() -> ImageReencoder.toJpeg(TestImages.pngHeaderOnly(8000, 5001)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Image is too large");
        assertThatThrownBy(() -> ImageReencoder.toJpeg(TestImages.pngHeaderOnly(100_000, 100_000)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Image is too large");
    }
}
