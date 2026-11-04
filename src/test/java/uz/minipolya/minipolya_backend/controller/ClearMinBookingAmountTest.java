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
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R41: {@code "clearMinBookingAmount": true} on the owner's stadium update — full payment only. */
@DatabaseTest
class ClearMinBookingAmountTest {

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
        owner = data.owner("Minimum Owner");
        ownerToken = data.bearer(owner);
        stadium = data.stadium(owner, "Minimum Arena");
        stadium.setMinBookingAmount(5_000_000L);
        stadium = stadiumRepository.saveAndFlush(stadium);
    }

    private ResultActions update(String json, String token) throws Exception {
        var request = put("/api/owner/stadiums/" + stadium.getId())
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header("Authorization", token);
        }
        return mvc.perform(request);
    }

    @Test
    void clearsTheMinimumAndAuditsIt() throws Exception {
        update("{\"clearMinBookingAmount\":true}", ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minBookingAmount").doesNotExist())
                .andExpect(jsonPath("$.data.playerMinBookingAmount").doesNotExist());
        assertThat(stadiumRepository.findById(stadium.getId()).orElseThrow().getMinBookingAmount()).isNull();

        String admin = data.bearer(data.admin("Minimum Admin"));
        mvc.perform(get("/api/admin/audit-log").header("Authorization", admin)
                        .param("entityId", stadium.getId().toString()).param("action", "STADIUM_UPDATED"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].changes[0].field").value("minBookingAmount"))
                .andExpect(jsonPath("$.data.content[0].changes[0].oldValue").value(5_000_000))
                .andExpect(jsonPath("$.data.content[0].changes[0].newValue").doesNotExist());
    }

    @Test
    void falseOrAbsentLeavesItAlone() throws Exception {
        update("{\"clearMinBookingAmount\":false,\"name\":\"Renamed\"}", ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minBookingAmount").value(5_000_000));
        update("{\"name\":\"Renamed again\"}", ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minBookingAmount").value(5_000_000));
        // A new value alone still sets it.
        update("{\"minBookingAmount\":6000000,\"clearMinBookingAmount\":false}", ownerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minBookingAmount").value(6_000_000));
    }

    @Test
    void bothAtOnceIs400() throws Exception {
        update("{\"minBookingAmount\":6000000,\"clearMinBookingAmount\":true}", ownerToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send either minBookingAmount or clearMinBookingAmount"));
        assertThat(stadiumRepository.findById(stadium.getId()).orElseThrow().getMinBookingAmount())
                .isEqualTo(5_000_000L);
    }

    @Test
    void accessRules() throws Exception {
        update("{\"clearMinBookingAmount\":true}", null).andExpect(status().isUnauthorized());
        update("{\"clearMinBookingAmount\":true}", data.bearer(data.user("Player"))).andExpect(status().isForbidden());
        update("{\"clearMinBookingAmount\":true}", data.bearer(data.owner("Other Owner")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not own this stadium"));
    }
}
