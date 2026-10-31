package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R11: an owner's legal details and ATMOS contract, and the approval that waits for it. */
@DatabaseTest
class AdminOwnerLegalDetailsTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private UserRepository userRepository;

    private User admin;
    private String adminToken;
    private User owner;

    @BeforeEach
    void setUp() {
        admin = data.admin("Legal Admin");
        adminToken = data.bearer(admin);
        owner = data.user("Would-be Owner");
    }

    private ResultActions putDetails(UUID userId, String json) throws Exception {
        return mvc.perform(put("/api/admin/users/" + userId + "/legal-details")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions setStatus(Stadium stadium, String status) throws Exception {
        return mvc.perform(put("/api/admin/stadiums/" + stadium.getId() + "/status")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"" + status + "\"}"));
    }

    @Test
    void accessIsForAdminsOnly() throws Exception {
        mvc.perform(get("/api/admin/users/" + owner.getId() + "/legal-details")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users/" + owner.getId() + "/legal-details")
                        .header("Authorization", data.bearer(owner)))
                .andExpect(status().isForbidden());
    }

    @Test
    void nothingEnteredYetIsDataNull() throws Exception {
        mvc.perform(get("/api/admin/users/" + owner.getId() + "/legal-details").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/admin/users/" + UUID.randomUUID() + "/legal-details").header("Authorization", adminToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("User not found"));
    }

    @Test
    void detailsAreSavedWithWhoChangedThemAndAudited() throws Exception {
        putDetails(owner.getId(), """
                {"legalType":"yatt","tin":"301234567","atmosContractStatus":"pending","atmosRecipientId":"ignored"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Legal details updated"))
                .andExpect(jsonPath("$.data.legalType").value("yatt"))
                .andExpect(jsonPath("$.data.tin").value("301234567"))
                .andExpect(jsonPath("$.data.atmosContractStatus").value("pending"))
                // only kept once the contract is signed
                .andExpect(jsonPath("$.data.atmosRecipientId").doesNotExist())
                .andExpect(jsonPath("$.data.updatedByName").value("Legal Admin"))
                .andExpect(jsonPath("$.data.updatedAt").exists());

        putDetails(owner.getId(), """
                {"legalType":"self_employed","tin":"31234567890123","atmosContractStatus":"signed","atmosRecipientId":"RCP-77"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.atmosRecipientId").value("RCP-77"));
        // The same again changes nothing and writes no entry.
        putDetails(owner.getId(), """
                {"legalType":"self_employed","tin":"31234567890123","atmosContractStatus":"signed","atmosRecipientId":"RCP-77"}""")
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/audit-log").header("Authorization", adminToken)
                        .param("action", "OWNER_LEGAL_DETAILS_CHANGED").param("entityId", owner.getId().toString()))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].entity.type").value("user"))
                .andExpect(jsonPath("$.data.content[0].changes[?(@.field=='atmosContractStatus')].oldValue")
                        .value("pending"))
                .andExpect(jsonPath("$.data.content[0].changes[?(@.field=='atmosContractStatus')].newValue")
                        .value("signed"));
    }

    @Test
    void invalidDetailsAreRefusedWithTheirOwnMessage() throws Exception {
        putDetails(owner.getId(), """
                {"legalType":"yatt","tin":"1234","atmosContractStatus":"none"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("tin must be 9 digits (or 14 for self-employed)"));
        putDetails(owner.getId(), """
                {"legalType":"mchj","tin":"31234567890123","atmosContractStatus":"none"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("tin must be 9 digits (or 14 for self-employed)"));
        putDetails(owner.getId(), """
                {"legalType":"self_employed","tin":"301234567","atmosContractStatus":"none"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("tin must be 9 digits (or 14 for self-employed)"));
        putDetails(owner.getId(), """
                {"legalType":"yatt","tin":"301234567","atmosContractStatus":"signed"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("atmosRecipientId is required when the contract is signed"));
        putDetails(owner.getId(), """
                {"legalType":"ooo","tin":"301234567","atmosContractStatus":"none"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("legalType must be one of yatt, mchj, self_employed"));
        putDetails(owner.getId(), """
                {"legalType":"yatt","tin":"301234567","atmosContractStatus":"maybe"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("atmosContractStatus must be one of none, pending, signed"));
        putDetails(UUID.randomUUID(), """
                {"legalType":"yatt","tin":"301234567","atmosContractStatus":"none"}""")
                .andExpect(status().isNotFound());
    }

    @Test
    void aStadiumIsApprovedOnlyOnceItsOwnersContractIsSigned() throws Exception {
        Stadium stadium = data.stadium(owner, "Gate Arena", "pending");

        setStatus(stadium, "approved")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The owner's ATMOS contract is not signed"));
        mvc.perform(get("/api/admin/stadiums/" + stadium.getId()).header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.ownerContractStatus").value("none"));

        putDetails(owner.getId(), """
                {"legalType":"mchj","tin":"301234567","atmosContractStatus":"pending"}""");
        setStatus(stadium, "approved").andExpect(status().isBadRequest());

        putDetails(owner.getId(), """
                {"legalType":"mchj","tin":"301234567","atmosContractStatus":"signed","atmosRecipientId":"RCP-1"}""");
        setStatus(stadium, "approved")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("approved"))
                .andExpect(jsonPath("$.data.ownerContractStatus").value("signed"));
        assertThat(userRepository.findById(owner.getId()).orElseThrow().isOwner()).isTrue();

        mvc.perform(get("/api/admin/stadiums").header("Authorization", adminToken).param("search", "Gate Arena"))
                .andExpect(jsonPath("$.data.content[0].ownerContractStatus").value("signed"));
    }
}
