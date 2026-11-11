package uz.minipolya.minipolya_backend.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ImageTypeTest {

    @Test
    void tellsTheThreeFormatsByTheirFirstBytes() {
        assertThat(ImageType.detect(new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xDB}))
                .contains(ImageType.JPEG);
        assertThat(ImageType.detect(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}))
                .contains(ImageType.PNG);
        assertThat(ImageType.detect(new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P'}))
                .contains(ImageType.WEBP);
    }

    @Test
    void anythingElseIsNone() {
        assertThat(ImageType.detect(null)).isEmpty();
        assertThat(ImageType.detect(new byte[0])).isEmpty();
        assertThat(ImageType.detect(new byte[]{(byte) 0xFF, (byte) 0xD8})).isEmpty();
        assertThat(ImageType.detect("GIF89a".getBytes())).isEmpty();
        // A RIFF that is not WebP (e.g. a WAV file).
        assertThat(ImageType.detect(new byte[]{'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E'})).isEmpty();
        assertThat(ImageType.detect("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes())).isEmpty();
    }

    @Test
    void extensionsAndContentTypes() {
        assertThat(ImageType.fromExtension("jpg")).contains(ImageType.JPEG);
        assertThat(ImageType.fromExtension("exe")).isEmpty();
        assertThat(ImageType.WEBP.contentType()).isEqualTo("image/webp");
    }
}
