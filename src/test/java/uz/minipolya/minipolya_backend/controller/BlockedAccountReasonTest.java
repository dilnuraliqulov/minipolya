package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R21: a blocked player is told why and when, on every request. */
@DatabaseTest
class BlockedAccountReasonTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;

    private void block(User admin, User player, String body) throws Exception {
        mvc.perform(put("/api/admin/users/" + player.getId()).header("Authorization", data.bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void theBlocked403CarriesTheLatestReasonAndTime() throws Exception {
        User admin = data.admin("Blocking Admin");
        User player = data.user("Blocked Player");
        String token = data.bearer(player);

        block(admin, player, "{\"isBlocked\":true,\"reason\":\"First reason\"}");
        block(admin, player, "{\"isBlocked\":false}");
        block(admin, player, "{\"isBlocked\":true,\"reason\":\"Spam bookings\"}");

        mvc.perform(get("/api/users/me").header("Authorization", token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"))
                .andExpect(jsonPath("$.message").value("Account is blocked"))
                .andExpect(jsonPath("$.data.reason").value("Spam bookings"))
                .andExpect(jsonPath("$.data.blockedAt").isString());
    }

    @Test
    void withoutAReasonOnlyTheTimeIsSent() throws Exception {
        User admin = data.admin("Blocking Admin 2");
        User player = data.user("Blocked Player 2");

        block(admin, player, "{\"isBlocked\":true}");

        mvc.perform(get("/api/users/me").header("Authorization", data.bearer(player)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"))
                .andExpect(jsonPath("$.data.reason").doesNotExist())
                .andExpect(jsonPath("$.data.blockedAt").isString());
    }
}
