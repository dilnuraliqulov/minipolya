package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.OwnerLegalDetailsRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R39: {@code GET /api/owner/legal-details} — the owner's own contract status, nothing secret. */
@DatabaseTest
class OwnerLegalDetailsTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private OwnerLegalDetailsRepository repository;

    private User owner;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        owner = data.owner("Legal Owner");
        ownerToken = data.bearer(owner);
    }

    @Test
    void nothingEnteredYetIsNone() throws Exception {
        mvc.perform(get("/api/owner/legal-details").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.atmosContractStatus").value("none"))
                .andExpect(jsonPath("$.data.ownerContractStatus").value("none"))
                .andExpect(jsonPath("$.data.legalType").doesNotExist());
    }

    @Test
    void showsTheLegalFormAndStatusButNoTaxOrRecipientId() throws Exception {
        repository.saveAndFlush(OwnerLegalDetails.builder()
                .userId(owner.getId())
                .legalType(OwnerLegalDetails.LegalType.MCHJ)
                .tin("123456789")
                .atmosContractStatus(OwnerLegalDetails.ContractStatus.SIGNED)
                .atmosRecipientId("recipient-42")
                .build());

        mvc.perform(get("/api/owner/legal-details").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.legalType").value("mchj"))
                .andExpect(jsonPath("$.data.atmosContractStatus").value("signed"))
                .andExpect(jsonPath("$.data.ownerContractStatus").value("signed"))
                .andExpect(jsonPath("$.data.tin").doesNotExist())
                .andExpect(jsonPath("$.data.atmosRecipientId").doesNotExist())
                .andExpect(jsonPath("$.data.updatedByName").doesNotExist());
    }

    @Test
    void ownersOnly() throws Exception {
        mvc.perform(get("/api/owner/legal-details")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/owner/legal-details").header("Authorization", data.bearer(data.user("Player"))))
                .andExpect(status().isForbidden());
    }
}
