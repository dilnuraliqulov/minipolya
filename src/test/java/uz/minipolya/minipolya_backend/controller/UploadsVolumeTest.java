package uz.minipolya.minipolya_backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;
import uz.minipolya.minipolya_backend.support.TestImages;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Photos live wherever {@code UPLOADS_DIR} points — on a deployed server an absolute path on
 * a mounted volume, e.g. {@code /data/uploads}, outside the release directory so a redeploy
 * cannot take them with it — and the URL stored for each one stays <b>relative</b>
 * ({@code /api/uploads/stadiums/…}) whatever that path is.
 *
 * <p>The relative URL is what lets the Mini App load photos through its own origin: the
 * frontend forwards {@code /api/**} to the backend's private address, so no photo URL ever
 * names the backend's host, and moving either service changes nothing in the database.
 */
@DatabaseTest
@TestPropertySource(properties = "app.uploads.dir=${java.io.tmpdir}/minipolya-uploads-volume-test")
class UploadsVolumeTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private AppProperties appProperties;

    @Test
    void anAbsoluteUploadsDirIsUsedAndTheStoredUrlStaysRelative() throws Exception {
        Path root = Path.of(appProperties.getUploads().getDir());
        assertThat(root).as("the configured uploads directory").isAbsolute();

        User owner = data.owner("Volume Owner");
        Stadium stadium = data.stadium(owner, "Volume Arena", "pending");

        String body = mvc.perform(multipart("/api/owner/stadiums/" + stadium.getId() + "/photos")
                        .file(new MockMultipartFile("file", "p.jpg", "image/jpeg", TestImages.jpeg(64, 48)))
                        .header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                // No host, no scheme, no uploads path: just the public API path.
                .andExpect(jsonPath("$.data.url").value(matchesPattern(
                        "/api/uploads/stadiums/" + stadium.getId() + "/[0-9a-f-]{36}\\.jpg")))
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.data.url");

        assertThat(url).doesNotContain(root.toString()).doesNotContain("http");

        // The bytes really are under the absolute directory, and still served from there.
        Path file = root.resolve(url.substring("/api/uploads/".length()));
        assertThat(file).exists();
        assertThat(file.startsWith(root)).isTrue();
        mvc.perform(get(url)).andExpect(status().isOk());

        Files.deleteIfExists(file);
    }
}
