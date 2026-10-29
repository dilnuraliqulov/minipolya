package uz.minipolya.minipolya_backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R1: with CORS_ALLOWED_ORIGINS set, exactly those origins may call the API from a browser. */
@DatabaseTest
@TestPropertySource(properties = "app.cors.allowed-origins=https://admin.minipolya.uz, https://app.minipolya.uz")
class CorsConfiguredTest {

    private static final String ADMIN_PANEL = "https://admin.minipolya.uz";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;

    @Test
    void aPreflightFromAnAllowedOriginPassesWithoutAToken() throws Exception {
        mvc.perform(options("/api/admin/stats")
                        .header("Origin", ADMIN_PANEL)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization, Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ADMIN_PANEL))
                .andExpect(header().string("Access-Control-Allow-Methods", containsString("DELETE")))
                .andExpect(header().string("Access-Control-Allow-Headers", containsString("Authorization")))
                .andExpect(header().string("Access-Control-Max-Age", "3600"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }

    @Test
    void theSecondConfiguredOriginIsAllowedToo() throws Exception {
        mvc.perform(options("/api/stadiums")
                        .header("Origin", "https://app.minipolya.uz")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "https://app.minipolya.uz"));
    }

    @Test
    void anActualRequestCarriesTheHeader() throws Exception {
        mvc.perform(get("/api/admin/stats")
                        .header("Origin", ADMIN_PANEL)
                        .header("Authorization", data.bearer(data.admin("Cors Admin"))))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", ADMIN_PANEL));
    }

    @Test
    void anyOtherOriginIsRefused() throws Exception {
        mvc.perform(options("/api/admin/stats")
                        .header("Origin", "https://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    void webhooksGetNoCors() throws Exception {
        mvc.perform(post("/webhooks/telegram").header("Origin", ADMIN_PANEL))
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
