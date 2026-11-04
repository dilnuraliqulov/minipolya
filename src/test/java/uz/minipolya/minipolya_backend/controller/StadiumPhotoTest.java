package uz.minipolya.minipolya_backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.minipolya.minipolya_backend.config.AppProperties;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R36: stadium photos — upload, delete, reorder, and the public files. */
@DatabaseTest
class StadiumPhotoTest {

    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F'};
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13};
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 4, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private StadiumRepository stadiumRepository;
    @Autowired
    private AppProperties appProperties;

    private User owner;
    private String ownerToken;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        owner = data.owner("Photo Owner");
        ownerToken = data.bearer(owner);
        stadium = data.stadium(owner, "Photo Arena", "pending");
    }

    private ResultActions upload(Stadium target, byte[] bytes, String contentType, String token) throws Exception {
        var request = multipart("/api/owner/stadiums/" + target.getId() + "/photos")
                .file(new MockMultipartFile("file", "../../evil name.exe", contentType, bytes));
        if (token != null) {
            request.header("Authorization", token);
        }
        return mvc.perform(request);
    }

    private String uploadedId(byte[] bytes) throws Exception {
        String body = upload(stadium, bytes, "image/jpeg", ownerToken).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private Path fileOf(String url) {
        String relative = url.substring("/api/uploads/".length());
        return Path.of(appProperties.getUploads().getDir()).toAbsolutePath().normalize().resolve(relative);
    }

    @Test
    void uploadsGoLastUnderARandomNameAndAreServedPublicly() throws Exception {
        String body = upload(stadium, JPEG, "image/jpeg", ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Photo uploaded"))
                .andExpect(jsonPath("$.data.position").value(0))
                .andExpect(jsonPath("$.data.url").value(matchesPattern(
                        "/api/uploads/stadiums/" + stadium.getId() + "/[0-9a-f-]{36}\\.jpg")))
                .andReturn().getResponse().getContentAsString();
        String jpegUrl = JsonPath.read(body, "$.data.url");
        assertThat(jpegUrl).doesNotContain("evil");

        // The content type the client claims does not matter: the bytes say PNG.
        upload(stadium, PNG, "image/jpeg", ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(1))
                .andExpect(jsonPath("$.data.url").value(containsString(".png")));
        upload(stadium, WEBP, "application/octet-stream", ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.position").value(2))
                .andExpect(jsonPath("$.data.url").value(containsString(".webp")));

        mvc.perform(get(jpegUrl))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Cache-Control", containsString("max-age=31536000")))
                .andExpect(content().bytes(JPEG));

        // The first photo is the cover.
        mvc.perform(get("/api/owner/stadiums").header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data[0].coverPhotoUrl").value(jpegUrl));
        mvc.perform(get("/api/owner/stadiums/" + stadium.getId()).header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data.photos", hasSize(3)))
                .andExpect(jsonPath("$.data.photos[0].url").value(jpegUrl));
    }

    @Test
    void onlyJpegPngAndWebpByTheirBytes() throws Exception {
        upload(stadium, "GIF89a not allowed".getBytes(), "image/gif", ownerToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported image type"));
        upload(stadium, "<html>hello</html>".getBytes(), "image/jpeg", ownerToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported image type"));
        upload(stadium, new byte[0], "image/png", ownerToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Unsupported image type"));
    }

    @Test
    void overTenMegabytesIs413() throws Exception {
        byte[] big = Arrays.copyOf(JPEG, 10 * 1024 * 1024 + 1);
        upload(stadium, big, "image/jpeg", ownerToken)
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.message").value("File too large"));
        byte[] exactly = Arrays.copyOf(JPEG, 10 * 1024 * 1024);
        upload(stadium, exactly, "image/jpeg", ownerToken).andExpect(status().isOk());
    }

    @Test
    void atMostTenPhotos() throws Exception {
        for (int i = 0; i < 10; i++) {
            stadium.addPhoto(StadiumPhoto.builder().url("https://cdn.example.com/" + i + ".jpg").position(i).build());
        }
        stadiumRepository.saveAndFlush(stadium);
        upload(stadium, JPEG, "image/jpeg", ownerToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A stadium can have at most 10 photos"));
    }

    @Test
    void aMissingFilePartIs400() throws Exception {
        mvc.perform(multipart("/api/owner/stadiums/" + stadium.getId() + "/photos")
                        .header("Authorization", ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("file is required"));
    }

    @Test
    void deleteRenumbersTheRestAndRemovesTheFileOnCommit() throws Exception {
        String first = uploadedId(JPEG);
        String second = uploadedId(PNG);
        String third = uploadedId(WEBP);
        Stadium reloaded = stadiumRepository.findById(stadium.getId()).orElseThrow();
        String firstUrl = reloaded.getPhotos().stream().filter(p -> p.getId().toString().equals(first))
                .findFirst().orElseThrow().getUrl();
        assertThat(fileOf(firstUrl)).exists();

        mvc.perform(delete("/api/owner/stadiums/" + stadium.getId() + "/photos/" + first)
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Photo deleted"))
                .andExpect(jsonPath("$.data").doesNotExist());

        mvc.perform(get("/api/owner/stadiums/" + stadium.getId()).header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data.photos", hasSize(2)))
                .andExpect(jsonPath("$.data.photos[0].id").value(second))
                .andExpect(jsonPath("$.data.photos[0].position").value(0))
                .andExpect(jsonPath("$.data.photos[1].id").value(third))
                .andExpect(jsonPath("$.data.photos[1].position").value(1));

        // The file goes only when the deletion commits (the test's transaction never does).
        assertThat(fileOf(firstUrl)).exists();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(fileOf(firstUrl)).doesNotExist();

        mvc.perform(delete("/api/owner/stadiums/" + stadium.getId() + "/photos/" + first)
                        .header("Authorization", ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Photo not found"));
    }

    @Test
    void anotherStadiumsPhotoIsNotFound() throws Exception {
        String photo = uploadedId(JPEG);
        Stadium other = data.stadium(owner, "Second Arena", "approved");
        mvc.perform(delete("/api/owner/stadiums/" + other.getId() + "/photos/" + photo)
                        .header("Authorization", ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Photo not found"));
    }

    @Test
    void reorderSetsThePositionsAndTheCover() throws Exception {
        String a = uploadedId(JPEG);
        String b = uploadedId(PNG);
        String c = uploadedId(WEBP);

        reorder("[\"" + c + "\",\"" + a + "\",\"" + b + "\"]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Photos reordered"))
                .andExpect(jsonPath("$.data[0].id").value(c))
                .andExpect(jsonPath("$.data[0].position").value(0))
                .andExpect(jsonPath("$.data[1].id").value(a))
                .andExpect(jsonPath("$.data[1].position").value(1))
                .andExpect(jsonPath("$.data[2].id").value(b))
                .andExpect(jsonPath("$.data[2].position").value(2));

        mvc.perform(get("/api/owner/stadiums").header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data[0].coverPhotoUrl").value(containsString(".webp")));

        String message = "photoIds must list every photo of the stadium exactly once";
        reorder("[\"" + c + "\",\"" + a + "\"]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
        reorder("[\"" + c + "\",\"" + a + "\",\"" + a + "\"]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
        reorder("[\"" + c + "\",\"" + a + "\",\"" + b + "\",\"" + b + "\"]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
        reorder("[\"" + c + "\",\"" + a + "\",\"00000000-0000-0000-0000-000000000000\"]")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(message));
        mvc.perform(put("/api/owner/stadiums/" + stadium.getId() + "/photos/order")
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    private ResultActions reorder(String ids) throws Exception {
        return mvc.perform(put("/api/owner/stadiums/" + stadium.getId() + "/photos/order")
                .header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"photoIds\":" + ids + "}"));
    }

    @Test
    void eachChangeIsAuditedAsAStadiumEdit() throws Exception {
        uploadedId(JPEG);
        String admin = data.bearer(data.admin("Photo Admin"));
        mvc.perform(get("/api/admin/audit-log").header("Authorization", admin)
                        .param("entityId", stadium.getId().toString()).param("action", "STADIUM_UPDATED"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].actor.type").value("OWNER"))
                .andExpect(jsonPath("$.data.content[0].changes[0].field").value("photos"));
    }

    @Test
    void onlyTheOwner() throws Exception {
        String photo = uploadedId(JPEG);
        String otherOwner = data.bearer(data.owner("Not The Owner"));
        String unknown = "/api/owner/stadiums/00000000-0000-0000-0000-000000000000";

        upload(stadium, JPEG, "image/jpeg", otherOwner).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not own this stadium"));
        mvc.perform(delete("/api/owner/stadiums/" + stadium.getId() + "/photos/" + photo)
                        .header("Authorization", otherOwner))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not own this stadium"));
        mvc.perform(put("/api/owner/stadiums/" + stadium.getId() + "/photos/order")
                        .header("Authorization", otherOwner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"photoIds\":[\"" + photo + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not own this stadium"));

        mvc.perform(multipart(unknown + "/photos")
                        .file(new MockMultipartFile("file", "a.jpg", "image/jpeg", JPEG))
                        .header("Authorization", ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Stadium not found"));
        mvc.perform(delete(unknown + "/photos/" + photo).header("Authorization", ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Stadium not found"));
        mvc.perform(put(unknown + "/photos/order").header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"photoIds\":[]}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Stadium not found"));

        upload(stadium, JPEG, "image/jpeg", data.bearer(data.user("Plain Player")))
                .andExpect(status().isForbidden());
        upload(stadium, JPEG, "image/jpeg", null).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/owner/stadiums/" + stadium.getId() + "/photos/" + photo))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/api/owner/stadiums/" + stadium.getId() + "/photos/order")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"photoIds\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anyStadiumStatus() throws Exception {
        for (String status : List.of("approved", "rejected", "blocked")) {
            Stadium s = data.stadium(owner, "Status " + status, status);
            upload(s, JPEG, "image/jpeg", ownerToken).andExpect(status().isOk());
        }
    }

    @Test
    void nothingElseUnderUploadsIsServed() throws Exception {
        String body = upload(stadium, JPEG, "image/jpeg", ownerToken)
                .andReturn().getResponse().getContentAsString();
        String url = JsonPath.read(body, "$.data.url");
        String dir = "/api/uploads/stadiums/" + stadium.getId();

        // A file planted next to the photos, under a name the storage never gives out.
        Path planted = fileOf(url).resolveSibling("secret.txt");
        Files.writeString(planted, "top secret");
        try {
            mvc.perform(get(dir + "/secret.txt")).andExpect(status().isNotFound());
            mvc.perform(get(dir)).andExpect(status().isNotFound());
            mvc.perform(get(dir + "/")).andExpect(status().isNotFound());
            mvc.perform(get("/api/uploads/stadiums")).andExpect(status().isNotFound());
            mvc.perform(get("/api/uploads/")).andExpect(status().isNotFound());
            mvc.perform(get("/api/uploads/stadiums/not-a-uuid/" + url.substring(url.lastIndexOf('/') + 1)))
                    .andExpect(status().is4xxClientError());

            for (String attack : List.of(
                    dir + "/../../../pom.xml",
                    dir + "/..%2F..%2F..%2Fpom.xml",
                    dir + "/%2e%2e/%2e%2e/%2e%2e/pom.xml",
                    dir + "/..\\..\\..\\pom.xml",
                    "/api/uploads/stadiums/" + stadium.getId() + "%2F..%2F..%2F..%2Fpom.xml")) {
                String served = mvc.perform(get(attack)).andExpect(status().is4xxClientError())
                        .andReturn().getResponse().getContentAsString();
                assertThat(served).doesNotContain("<project").doesNotContain("top secret");
            }
        } finally {
            Files.deleteIfExists(planted);
        }
    }
}
