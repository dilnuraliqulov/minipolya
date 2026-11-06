package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D3: {@code PUT /api/admin/stadiums/{id}/booking-terms} — the deposit and the cancellation
 * window, agreed with the owner by phone and set by an admin (owners no longer can, R41 moved).
 */
@DatabaseTest
class AdminStadiumBookingTermsTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private StadiumRepository stadiumRepository;

    private String adminToken;
    private User owner;
    private Stadium stadium; // 200 000 so'm an hour, no minimum, 24 h

    @BeforeEach
    void setUp() {
        adminToken = data.bearer(data.admin("Terms Admin"));
        owner = data.owner("Terms Owner");
        stadium = data.stadium(owner, "Terms Arena", "pending");
    }

    private ResultActions terms(UUID id, String json, String token) throws Exception {
        var request = put("/api/admin/stadiums/" + id + "/booking-terms")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header("Authorization", token);
        }
        return mvc.perform(request);
    }

    private ResultActions terms(String json) throws Exception {
        return terms(stadium.getId(), json, adminToken);
    }

    private ResultActions auditEntries() throws Exception {
        return mvc.perform(get("/api/admin/audit-log").header("Authorization", adminToken)
                .param("entityId", stadium.getId().toString()).param("action", "STADIUM_BOOKING_TERMS_CHANGED"));
    }

    @Test
    void setsBothTermsAndAnswersTheAdminDetail() throws Exception {
        terms("{\"minBookingAmount\":5000000,\"cancellationHours\":12}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Booking terms updated"))
                .andExpect(jsonPath("$.data.id").value(stadium.getId().toString()))
                .andExpect(jsonPath("$.data.minBookingAmount").value(5_000_000))
                .andExpect(jsonPath("$.data.playerMinBookingAmount").isNumber())
                .andExpect(jsonPath("$.data.cancellationHours").value(12))
                .andExpect(jsonPath("$.data.cancellationPolicy.cancellationHours").value(12))
                .andExpect(jsonPath("$.data.ownerContractStatus").exists());
        Stadium stored = stadiumRepository.findById(stadium.getId()).orElseThrow();
        assertThat(stored.getMinBookingAmount()).isEqualTo(5_000_000L);
        assertThat(stored.getCancellationHours()).isEqualTo(12);

        auditEntries()
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].actor.type").value("ADMIN"))
                .andExpect(jsonPath("$.data.content[0].entity.type").value("stadium"))
                .andExpect(jsonPath("$.data.content[0].changes[0].field").value("minBookingAmount"))
                .andExpect(jsonPath("$.data.content[0].changes[0].oldValue").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].changes[0].newValue").value(5_000_000))
                .andExpect(jsonPath("$.data.content[0].changes[1].field").value("cancellationHours"))
                .andExpect(jsonPath("$.data.content[0].changes[1].oldValue").value(24))
                .andExpect(jsonPath("$.data.content[0].changes[1].newValue").value(12));
    }

    @Test
    void aNullMinimumMeansThePlatformDefault() throws Exception {
        stadium.setMinBookingAmount(7_000_000L);
        stadiumRepository.saveAndFlush(stadium);

        // T2: null = use the platform default (5 000 000 tiyin): the stored value is cleared,
        // the player figure is the default's (50 000 -> 55 000 so'm).
        terms("{\"minBookingAmount\":null,\"cancellationHours\":24}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minBookingAmount").doesNotExist())
                .andExpect(jsonPath("$.data.playerMinBookingAmount").value(5_500_000));
        assertThat(stadiumRepository.findById(stadium.getId()).orElseThrow().getMinBookingAmount()).isNull();
        // Absent is the same as null: the body states the whole terms.
        stadium = stadiumRepository.findById(stadium.getId()).orElseThrow();
        terms("{\"cancellationHours\":6}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minBookingAmount").doesNotExist());
    }

    @Test
    void aMinimumNotAboveTheTeamFeeIsRefused() throws Exception {
        expect400("{\"minBookingAmount\":2500000,\"cancellationHours\":24}",
                "minBookingAmount must be more than the team game platform fee (25 000 so'm)");
        expect400("{\"minBookingAmount\":100000,\"cancellationHours\":24}",
                "minBookingAmount must be more than the team game platform fee (25 000 so'm)");
        terms("{\"minBookingAmount\":2500001,\"cancellationHours\":24}").andExpect(status().isOk());
    }

    @Test
    void nothingChangedWritesNoAuditEntry() throws Exception {
        terms("{\"minBookingAmount\":null,\"cancellationHours\":24}").andExpect(status().isOk());
        auditEntries().andExpect(jsonPath("$.data.content", hasSize(0)));
    }

    @Test
    void theRules() throws Exception {
        expect400("{\"minBookingAmount\":49999,\"cancellationHours\":24}",
                "minBookingAmount must be at least 50000 tiyin (500 so'm)");
        expect400("{\"minBookingAmount\":20000001,\"cancellationHours\":24}",
                "minBookingAmount must not be more than pricePerHour");
        expect400("{\"minBookingAmount\":5000000}", "cancellationHours is required");
        expect400("{\"cancellationHours\":-1}", "cancellationHours must be between 0 and 168");
        expect400("{\"cancellationHours\":169}", "cancellationHours must be between 0 and 168");
        terms("{\"minBookingAmount\":2600000,\"cancellationHours\":0}").andExpect(status().isOk());
        terms("{\"minBookingAmount\":20000000,\"cancellationHours\":168}").andExpect(status().isOk());

        Stadium stored = stadiumRepository.findById(stadium.getId()).orElseThrow();
        assertThat(stored.getMinBookingAmount()).isEqualTo(20_000_000L);
        assertThat(stored.getCancellationHours()).isEqualTo(168);
    }

    private void expect400(String json, String message) throws Exception {
        terms(json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
    }

    @Test
    void anyStatus() throws Exception {
        for (String status : List.of("approved", "rejected", "blocked")) {
            Stadium s = data.stadium(owner, "Terms " + status, status);
            terms(s.getId(), "{\"minBookingAmount\":3000000,\"cancellationHours\":3}", adminToken)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value(status));
        }
    }

    @Test
    void adminsOnly() throws Exception {
        String body = "{\"minBookingAmount\":null,\"cancellationHours\":24}";
        terms(stadium.getId(), body, null).andExpect(status().isUnauthorized());
        terms(stadium.getId(), body, data.bearer(owner)).andExpect(status().isForbidden());
        terms(stadium.getId(), body, data.bearer(data.user("Terms Player"))).andExpect(status().isForbidden());
        terms(UUID.randomUUID(), body, adminToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Stadium not found"));
    }

    @Test
    void theOwnerSeesTheTermsButCannotChangeThem() throws Exception {
        terms("{\"minBookingAmount\":5000000,\"cancellationHours\":12}").andExpect(status().isOk());
        String ownerToken = data.bearer(owner);
        mvc.perform(put("/api/owner/stadiums/" + stadium.getId()).header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearMinBookingAmount\":true,\"cancellationHours\":48}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minBookingAmount").value(5_000_000))
                .andExpect(jsonPath("$.data.cancellationPolicy.cancellationHours").value(12));
    }
}
