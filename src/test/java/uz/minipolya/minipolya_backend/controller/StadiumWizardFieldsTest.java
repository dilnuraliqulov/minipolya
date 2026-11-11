package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Changeset 048: a stadium's landmark ("Moʻljal") and the four new amenities. */
@DatabaseTest
class StadiumWizardFieldsTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private StadiumRepository stadiumRepository;

    private User owner;
    private String ownerToken;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        owner = data.owner("Wizard Owner");
        ownerToken = data.bearer(owner);
        stadium = data.stadium(owner, "Wizard Arena");
    }

    private ResultActions update(String json) throws Exception {
        return mvc.perform(put("/api/owner/stadiums/" + stadium.getId()).header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void landmarkAndTheNewAmenitiesAreStoredAndShownEverywhere() throws Exception {
        update("""
                {"landmark":"  Korzinka yonida  ",
                 "amenities":{"parking":true,"toilet":true,"covered":true,"bibs":true,"drinkingWater":true}}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.landmark").value("Korzinka yonida"))
                .andExpect(jsonPath("$.data.amenities.toilet").value(true))
                .andExpect(jsonPath("$.data.amenities.covered").value(true))
                .andExpect(jsonPath("$.data.amenities.bibs").value(true))
                .andExpect(jsonPath("$.data.amenities.drinkingWater").value(true))
                .andExpect(jsonPath("$.data.amenities.wifi").value(false));

        Stadium stored = stadiumRepository.findById(stadium.getId()).orElseThrow();
        assertThat(stored.getLandmark()).isEqualTo("Korzinka yonida");
        assertThat(stored.getAmenities().isDrinkingWater()).isTrue();

        mvc.perform(get("/api/owner/stadiums").header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data[0].landmark").value("Korzinka yonida"));
        mvc.perform(get("/api/stadiums/" + stadium.getId()))
                .andExpect(jsonPath("$.data.landmark").value("Korzinka yonida"))
                .andExpect(jsonPath("$.data.amenities.covered").value(true));
        String admin = data.bearer(data.admin("Wizard Admin"));
        mvc.perform(get("/api/admin/stadiums/" + stadium.getId()).header("Authorization", admin))
                .andExpect(jsonPath("$.data.landmark").value("Korzinka yonida"))
                .andExpect(jsonPath("$.data.amenities.bibs").value(true));

        mvc.perform(get("/api/admin/audit-log").header("Authorization", admin)
                        .param("entityId", stadium.getId().toString()).param("action", "STADIUM_UPDATED"))
                .andExpect(jsonPath("$.data.content[0].changes[*].field", hasItems(
                        "landmark", "amenities.toilet", "amenities.covered", "amenities.bibs",
                        "amenities.drinkingWater")));
    }

    @Test
    void aBlankLandmarkClearsIt() throws Exception {
        update("{\"landmark\":\"Metro\"}").andExpect(status().isOk());
        update("{\"landmark\":\"   \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.landmark").doesNotExist());
        assertThat(stadiumRepository.findById(stadium.getId()).orElseThrow().getLandmark()).isNull();
    }

    @Test
    void aLandmarkOver150CharactersIs400() throws Exception {
        update("{\"landmark\":\"" + "x".repeat(151) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("landmark must be at most 150 characters"));
    }
}
