package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The host's health check: {@code GET /actuator/health} answers without credentials and
 * says nothing beyond the status, and health is the only actuator endpoint exposed.
 */
@DatabaseTest
class HealthEndpointTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void healthIsPublicAndSaysOnlyTheStatus() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                // show-details / show-components are never: no component or detail map leaks.
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("postgres"))));
    }

    @Test
    void noOtherActuatorEndpointIsExposed() throws Exception {
        // Not exposed, so they never reach a handler; without a JWT the filter chain
        // answers 401 (anyRequest().authenticated()) rather than serving anything.
        for (String path : new String[] {"/actuator", "/actuator/env", "/actuator/beans",
                "/actuator/configprops", "/actuator/loggers", "/actuator/metrics"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        }
    }
}
