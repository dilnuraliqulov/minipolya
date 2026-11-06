package uz.minipolya.minipolya_backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumAmenities;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R35: {@code GET /api/owner/stadiums/{id}} — the owner's own stadium in any status, as PUT answers it. */
@DatabaseTest
class OwnerStadiumDetailTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private StadiumRepository stadiumRepository;

    private User owner;
    private String ownerToken;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        owner = data.owner("Detail Owner");
        ownerToken = data.bearer(owner);
        stadium = data.stadium(owner, "Detail Arena", "pending");
        stadium.setMinBookingAmount(5_000_000L);
        stadium.setPhone("+998901234567");
        stadium.setDescription("Covered pitch");
        stadium.setCancellationHours(12);
        stadium.setAmenities(StadiumAmenities.builder().parking(true).build());
        stadium.addPhoto(StadiumPhoto.builder().url("/api/uploads/stadiums/x/a.jpg").position(0).build());
        stadium = stadiumRepository.saveAndFlush(stadium);
    }

    @Test
    void theOwnerSeesTheirPendingStadiumWithOwnerAndPlayerPrices() throws Exception {
        mvc.perform(get("/api/owner/stadiums/" + stadium.getId()).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(stadium.getId().toString()))
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.pricePerHour").value(20_000_000))
                .andExpect(jsonPath("$.data.playerPrice").value(20_500_000))
                .andExpect(jsonPath("$.data.minBookingAmount").value(5_000_000))
                .andExpect(jsonPath("$.data.playerMinBookingAmount").isNumber())
                .andExpect(jsonPath("$.data.openTime").value("08:00:00"))
                .andExpect(jsonPath("$.data.closeTime").value("23:00:00"))
                .andExpect(jsonPath("$.data.cancellationPolicy.cancellationHours").value(12))
                .andExpect(jsonPath("$.data.phone").value("+998901234567"))
                .andExpect(jsonPath("$.data.description").value("Covered pitch"))
                .andExpect(jsonPath("$.data.photos[0].url").value("/api/uploads/stadiums/x/a.jpg"))
                .andExpect(jsonPath("$.data.amenities.parking").value(true));
    }

    @Test
    void itIsExactlyWhatPutAnswers() throws Exception {
        String putBody = mvc.perform(put("/api/owner/stadiums/" + stadium.getId())
                        .header("Authorization", ownerToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String getBody = mvc.perform(get("/api/owner/stadiums/" + stadium.getId())
                        .header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> fromPut = JsonPath.read(putBody, "$.data");
        Map<String, Object> fromGet = JsonPath.read(getBody, "$.data");
        assertThat(fromGet).isEqualTo(fromPut);
    }

    @Test
    void aRejectedStadiumCarriesItsReason() throws Exception {
        stadium.setStatus("rejected");
        stadium.setStatusReason("Address not found");
        stadiumRepository.saveAndFlush(stadium);

        mvc.perform(get("/api/owner/stadiums/" + stadium.getId()).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("rejected"))
                .andExpect(jsonPath("$.data.statusReason").value("Address not found"));
    }

    @Test
    void notYourStadiumIs403() throws Exception {
        String otherOwner = data.bearer(data.owner("Other Owner"));
        mvc.perform(get("/api/owner/stadiums/" + stadium.getId()).header("Authorization", otherOwner))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not own this stadium"));
    }

    @Test
    void unknownStadiumIs404() throws Exception {
        mvc.perform(get("/api/owner/stadiums/00000000-0000-0000-0000-000000000000")
                        .header("Authorization", ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Stadium not found"));
    }

    @Test
    void aPlayerIs403AndNoTokenIs401() throws Exception {
        mvc.perform(get("/api/owner/stadiums/" + stadium.getId())
                        .header("Authorization", data.bearer(data.user("Just A Player"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/owner/stadiums/" + stadium.getId())).andExpect(status().isUnauthorized());
    }
}
