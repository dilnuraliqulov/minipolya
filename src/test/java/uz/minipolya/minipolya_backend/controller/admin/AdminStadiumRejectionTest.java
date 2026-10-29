package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R10: a stadium turned down at review is rejected — invisible to players, explained to its owner. */
@DatabaseTest
class AdminStadiumRejectionTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;

    private String adminToken;
    private User owner;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        adminToken = data.bearer(data.admin("Reject Admin"));
        owner = data.owner("Rejected Owner");
        stadium = data.stadium(owner, "Rejected Arena", "pending");
    }

    private org.springframework.test.web.servlet.ResultActions setStatus(String json) throws Exception {
        return mvc.perform(put("/api/admin/stadiums/" + stadium.getId() + "/status")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void aRejectedStadiumIsHiddenFromPlayersAndExplainedToItsOwner() throws Exception {
        setStatus("{\"status\":\"rejected\",\"reason\":\"Photos do not show the pitch\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("rejected"))
                .andExpect(jsonPath("$.data.statusReason").value("Photos do not show the pitch"));

        mvc.perform(get("/api/stadiums/" + stadium.getId())).andExpect(status().isNotFound());
        mvc.perform(get("/api/stadiums").param("q", "Rejected Arena"))
                .andExpect(jsonPath("$.data.content", hasSize(0)));

        mvc.perform(get("/api/owner/stadiums").header("Authorization", data.bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("rejected"))
                .andExpect(jsonPath("$.data[0].statusReason").value("Photos do not show the pitch"));

        mvc.perform(get("/api/admin/stadiums").header("Authorization", adminToken)
                        .param("status", "rejected").param("search", "Rejected Arena"))
                .andExpect(jsonPath("$.data.content", hasSize(1)));
        mvc.perform(get("/api/admin/stats").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.rejectedStadiums").value(1));
        mvc.perform(get("/api/admin/audit-log").header("Authorization", adminToken)
                        .param("entityId", stadium.getId().toString()).param("action", "STADIUM_REJECTED"))
                .andExpect(jsonPath("$.data.content[0].reason").value("Photos do not show the pitch"));
    }

    @Test
    void theRulesOfRejecting() throws Exception {
        setStatus("{\"status\":\"rejected\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reason is required"));
        setStatus("{\"status\":\"blocked\",\"reason\":\"\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reason is required"));
        setStatus("{\"status\":\"archived\",\"reason\":\"x\"}")
                .andExpect(status().isBadRequest());

        setStatus("{\"status\":\"rejected\",\"reason\":\"Wrong address\"}").andExpect(status().isOk());
        setStatus("{\"status\":\"rejected\",\"reason\":\"Again\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Only a pending stadium can be rejected"));

        // Back to review: the reason goes with it.
        setStatus("{\"status\":\"pending\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.statusReason").doesNotExist());
    }
}
