package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R42: a user who is not an owner yet can add a stadium and follow its review — exactly
 * {@code POST} and {@code GET /api/owner/stadiums}; every other owner endpoint still needs
 * the owner role.
 */
@DatabaseTest
class OwnerStadiumOnboardingTest {

    private static final String STADIUM = """
            {"name":"Newcomer Arena","address":"Yunusobod 4","format":"5x5","surfaceType":"artificial",
             "openTime":"08:00","closeTime":"23:00","pricePerHour":15000000,"district":"Yunusobod"}
            """;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private StadiumRepository stadiumRepository;
    @Autowired
    private UserRepository userRepository;

    private User player;
    private String playerToken;

    @BeforeEach
    void setUp() {
        player = data.user("Would-be Owner");
        playerToken = data.bearer(player);
    }

    @Test
    void aPlayerAddsAStadiumAndSeesItPending() throws Exception {
        mvc.perform(post("/api/owner/stadiums").header("Authorization", playerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(STADIUM))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.message").value("Stadium submitted for review"));

        mvc.perform(get("/api/owner/stadiums").header("Authorization", playerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].name").value("Newcomer Arena"))
                .andExpect(jsonPath("$.data[0].status").value("pending"));

        // Still a player until an admin approves the stadium.
        assertThat(userRepository.findById(player.getId()).orElseThrow().isOwner()).isFalse();
    }

    @Test
    void aPlayerSeesTheReasonTheirStadiumWasRejected() throws Exception {
        Stadium stadium = data.stadium(player, "Turned Down", "rejected");
        stadium.setStatusReason("No photos of the pitch");
        stadiumRepository.save(stadium);

        mvc.perform(get("/api/owner/stadiums").header("Authorization", playerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("rejected"))
                .andExpect(jsonPath("$.data[0].statusReason").value("No photos of the pitch"));
    }

    @Test
    void everyOtherOwnerEndpointStillNeedsTheOwnerRole() throws Exception {
        Stadium stadium = data.stadium(player, "Pending Pitch", "pending");
        UUID id = stadium.getId();

        mvc.perform(get("/api/owner/stadiums/" + id + "/pricing").header("Authorization", playerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/owner/dashboard/stadiums").header("Authorization", playerToken))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/owner/stadiums/" + id).header("Authorization", playerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/owner/stadiums/" + id + "/closures").header("Authorization", playerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void withoutATokenBothAre401() throws Exception {
        mvc.perform(post("/api/owner/stadiums").contentType(MediaType.APPLICATION_JSON).content(STADIUM))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/owner/stadiums")).andExpect(status().isUnauthorized());
    }

    @Test
    void anOwnerStillAddsAndListsAsBefore() throws Exception {
        String ownerToken = data.bearer(data.owner("Existing Owner"));
        mvc.perform(post("/api/owner/stadiums").header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(STADIUM))
                .andExpect(status().isOk());
        mvc.perform(get("/api/owner/stadiums").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)));
    }
}
