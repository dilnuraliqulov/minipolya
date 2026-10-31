package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R12: admins' internal notes on a stadium — never seen by its owner or players. */
@DatabaseTest
class AdminStadiumNotesTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;

    private User admin;
    private String adminToken;
    private User owner;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        admin = data.admin("Note Taker");
        adminToken = data.bearer(admin);
        owner = data.owner("Noted Owner");
        stadium = data.stadium(owner, "Noted Arena", "pending");
    }

    private ResultActions addNote(UUID stadiumId, String json) throws Exception {
        return mvc.perform(post("/api/admin/stadiums/" + stadiumId + "/notes")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void notesAreAddedAndListedNewestFirst() throws Exception {
        addNote(stadium.getId(), "{\"text\":\"28.10 qo'ng'iroq qilindi\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Note added"))
                .andExpect(jsonPath("$.data.text").value("28.10 qo'ng'iroq qilindi"))
                .andExpect(jsonPath("$.data.authorId").value(admin.getId().toString()))
                .andExpect(jsonPath("$.data.authorName").value("Note Taker"))
                .andExpect(jsonPath("$.data.createdAt").exists());
        addNote(stadium.getId(), "{\"text\":\"Juma kuni shartnoma imzolanadi\"}").andExpect(status().isOk());

        mvc.perform(get("/api/admin/stadiums/" + stadium.getId() + "/notes").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].text").value("Juma kuni shartnoma imzolanadi"));

        // Audited, but without what the note says.
        mvc.perform(get("/api/admin/audit-log").header("Authorization", adminToken)
                        .param("action", "STADIUM_NOTE_ADDED").param("entityId", stadium.getId().toString()))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].reason").value("note added"))
                .andExpect(content().string(not(containsString("shartnoma"))));
    }

    @Test
    void ownersAndPlayersNeverSeeThem() throws Exception {
        addNote(stadium.getId(), "{\"text\":\"Secret review remark\"}").andExpect(status().isOk());

        mvc.perform(get("/api/admin/stadiums/" + stadium.getId() + "/notes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/stadiums/" + stadium.getId() + "/notes").header("Authorization", data.bearer(owner)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/owner/stadiums").header("Authorization", data.bearer(owner)))
                .andExpect(content().string(not(containsString("Secret review remark"))));
    }

    @Test
    void invalidNotesAreRefused() throws Exception {
        addNote(stadium.getId(), "{\"text\":\"   \"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("text is required"));
        addNote(stadium.getId(), "{\"text\":\"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("text must be at most 1000 characters"));
        addNote(UUID.randomUUID(), "{\"text\":\"Somewhere\"}")
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Stadium not found"));
        mvc.perform(get("/api/admin/stadiums/" + UUID.randomUUID() + "/notes").header("Authorization", adminToken))
                .andExpect(status().isNotFound());
    }
}
