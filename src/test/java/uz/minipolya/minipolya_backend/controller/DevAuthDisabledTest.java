package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R19: without the dev profile the dev sign-in does not exist. */
@DatabaseTest
class DevAuthDisabledTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void outsideTheDevProfileBothPathsAre404() throws Exception {
        mvc.perform(get("/api/auth/dev/users"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Not found"));
        mvc.perform(post("/api/auth/dev/login").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"x\"}"))
                .andExpect(status().isNotFound());
    }
}
