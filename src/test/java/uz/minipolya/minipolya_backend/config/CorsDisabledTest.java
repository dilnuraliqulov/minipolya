package uz.minipolya.minipolya_backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R1: with no CORS_ALLOWED_ORIGINS, the default, nothing changes — no CORS headers at all. */
@DatabaseTest
class CorsDisabledTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;

    @Test
    void aPreflightGetsNoCorsHeaders() throws Exception {
        mvc.perform(options("/api/admin/stats")
                        .header("Origin", "https://admin.minipolya.uz")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void anActualRequestWorksAsBeforeWithoutTheHeader() throws Exception {
        mvc.perform(get("/api/admin/stats")
                        .header("Origin", "https://admin.minipolya.uz")
                        .header("Authorization", data.bearer(data.admin("No Cors Admin"))))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
