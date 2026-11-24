package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/admin/payments/provider-check}: which card provider this server talks to
 * and whether it answers. The tests run on the fake provider, which is what a developer's
 * machine and a plain staging server have; the real client's own answers (a fresh token, a
 * refused one, a timeout) are {@code AtmosPaymentProviderTest}'s.
 */
@DatabaseTest
class AdminProviderCheckTest {

    private static final String PATH = "/api/admin/payments/provider-check";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;

    @Test
    void withoutATokenItIs401AndANonAdminGets403() throws Exception {
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).header("Authorization", data.bearer(data.user("Curious Player"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(PATH).header("Authorization", data.bearer(data.owner("Curious Owner"))))
                .andExpect(status().isForbidden());
    }

    /**
     * Also proves the literal path wins over {@code /api/admin/payments/{id}}: read as an id
     * it could never be a UUID and this would not be a 200.
     */
    @Test
    void theFakeProviderAnswersOkAsFakeWithNoHost() throws Exception {
        mvc.perform(get(PATH).header("Authorization", data.bearer(data.admin("Check Admin"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("ok"))
                .andExpect(jsonPath("$.data.provider").value("fake"))
                // It reaches nothing, so there is no host, and nothing failed.
                .andExpect(jsonPath("$.data.host").doesNotExist())
                .andExpect(jsonPath("$.data.errorCode").doesNotExist());
    }

    /** Never a token, never a credential — the whole body is four known fields at most. */
    @Test
    void theAnswerCarriesNothingSecret() throws Exception {
        String body = mvc.perform(get(PATH)
                        .header("Authorization", data.bearer(data.admin("Careful Admin"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContainIgnoringCase("token")
                .doesNotContainIgnoringCase("secret")
                .doesNotContainIgnoringCase("consumer")
                .doesNotContainIgnoringCase("authorization")
                .doesNotContainIgnoringCase("bearer")
                .doesNotContainIgnoringCase("password");
    }
}
