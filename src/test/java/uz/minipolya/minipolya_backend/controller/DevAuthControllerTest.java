package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R19: the dev-only sign-in, under the dev profile. */
@DatabaseTest
@ActiveProfiles("dev")
class DevAuthControllerTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private UserRepository userRepository;

    private org.springframework.test.web.servlet.ResultActions login(String userId) throws Exception {
        return mvc.perform(post("/api/auth/dev/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + userId + "\"}"));
    }

    @Test
    void theUserListShowsTheNewestUsersWithTheirFlags() throws Exception {
        User owner = data.owner("Dev Owner");

        mvc.perform(get("/api/auth/dev/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(owner.getId().toString()))
                .andExpect(jsonPath("$.data[0].name").value("Dev Owner"))
                .andExpect(jsonPath("$.data[0].isOwner").value(true))
                .andExpect(jsonPath("$.data[0].isAdmin").value(false))
                .andExpect(jsonPath("$.data[0].isBlocked").value(false));
    }

    @Test
    void signingInReturnsATokenThatWorks() throws Exception {
        User player = data.user("Dev Player");

        MvcResult result = login(player.getId().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.id").value(player.getId().toString()))
                .andExpect(jsonPath("$.data.token").isString())
                .andReturn();
        String token = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.token");

        mvc.perform(get("/api/users/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Dev Player"));
    }

    @Test
    void theErrorCases() throws Exception {
        login("not-a-uuid").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid id"));
        login(UUID.randomUUID().toString()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("User not found"));

        User blocked = data.user("Dev Blocked");
        blocked.setBlocked(true);
        userRepository.save(blocked);
        login(blocked.getId().toString()).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"))
                .andExpect(jsonPath("$.message").value("Account is blocked"));
    }
}
