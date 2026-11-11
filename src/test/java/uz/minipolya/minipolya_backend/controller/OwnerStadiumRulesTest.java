package uz.minipolya.minipolya_backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The add-stadium rules through the API (D1, D2, D8, D12–D15): what create and update
 * refuse, and that the fields moved to the admin (booking terms) or the upload endpoint
 * (photos) are ignored when an older client still sends them.
 */
@DatabaseTest
class OwnerStadiumRulesTest {

    private static final String VALID = """
            {"name":"  Rules Arena ","address":"Chilonzor 9, 12","district":"Chilonzor","landmark":"Metro yonida",
             "phone":"+998901234567","latitude":41.2856,"longitude":69.2034,"description":"Covered pitch",
             "format":"5x5","surfaceType":"artificial","openTime":"08:00","closeTime":"23:00",
             "pricePerHour":15000000,"amenities":{"parking":true,"toilet":true}}""";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private StadiumRepository stadiumRepository;

    private User owner;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        owner = data.owner("Rules Owner");
        ownerToken = data.bearer(owner);
    }

    private ResultActions create(String json) throws Exception {
        return mvc.perform(post("/api/owner/stadiums").header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions update(UUID id, String json) throws Exception {
        return mvc.perform(put("/api/owner/stadiums/" + id).header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void aCompleteStadiumIsCreatedTrimmed() throws Exception {
        create(VALID)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Rules Arena"))
                .andExpect(jsonPath("$.data.phone").value("+998901234567"))
                .andExpect(jsonPath("$.data.landmark").value("Metro yonida"))
                .andExpect(jsonPath("$.data.latitude").value(41.2856))
                .andExpect(jsonPath("$.data.amenities.toilet").value(true))
                .andExpect(jsonPath("$.data.cancellationPolicy.cancellationHours").value(24))
                .andExpect(jsonPath("$.data.minBookingAmount").doesNotExist());
    }

    @Test
    void createRefusesWhatTheRulesRefuse() throws Exception {
        expect400(VALID.replace("\"phone\":\"+998901234567\",", ""), "phone is required");
        expect400(VALID.replace("+998901234567", "90 123 45 67"), "phone must be in the format +998XXXXXXXXX");
        expect400(VALID.replace("\"district\":\"Chilonzor\",", ""), "district is required");
        expect400(VALID.replace("\"latitude\":41.2856,", ""), "latitude and longitude are required");
        expect400(VALID.replace("41.2856", "55.7558"), "Location must be inside Uzbekistan");
        expect400(VALID.replace("15000000", "500000"), "pricePerHour must be between 10 000 and 5 000 000 so'm");
        expect400(VALID.replace("\"08:00\"", "\"08:30\""), "openTime and closeTime must be on the hour");
        expect400(VALID.replace("\"23:00\"", "\"02:00\""),
                "closeTime must be after openTime; closing after midnight is not supported");
        expect400(VALID.replace("  Rules Arena ", " ab "), "name must be between 3 and 150 characters");
        expect400(VALID.replace("Chilonzor 9, 12", "abc"), "address must be between 5 and 300 characters");
        expect400(VALID.replace("Covered pitch", "x".repeat(501)), "description must be at most 500 characters");
        assertThat(stadiumRepository.findByOwnerId(owner.getId())).isEmpty();
    }

    private void expect400(String json, String message) throws Exception {
        create(json).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(message));
    }

    @Test
    void bookingTermsAndPhotoUrlsSentOnCreateAreIgnored() throws Exception {
        String body = create(VALID.replace("\"pricePerHour\":15000000,", """
                        "pricePerHour":15000000,"minBookingAmount":5000000,"cancellationHours":2,
                        "clearMinBookingAmount":true,"photos":["https://evil.example.com/x.jpg"],"""))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Stadium stored = stadiumRepository.findById(UUID.fromString(JsonPath.read(body, "$.data.id"))).orElseThrow();
        assertThat(stored.getMinBookingAmount()).isNull();
        assertThat(stored.getCancellationHours()).isEqualTo(24);
        assertThat(stored.getPhotos()).isEmpty();
    }

    @Test
    void bookingTermsAndPhotoUrlsSentOnUpdateAreIgnored() throws Exception {
        Stadium stadium = data.stadium(owner, "Terms Arena");
        stadium.setMinBookingAmount(5_000_000L);
        stadium.setCancellationHours(12);
        stadium.addPhoto(StadiumPhoto.builder().url("/api/uploads/stadiums/x/a.jpg").position(0).build());
        stadiumRepository.saveAndFlush(stadium);

        update(stadium.getId(), """
                {"minBookingAmount":9000000,"cancellationHours":1,"clearMinBookingAmount":true,
                 "photos":["https://evil.example.com/x.jpg"]}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.minBookingAmount").value(5_000_000))
                .andExpect(jsonPath("$.data.cancellationPolicy.cancellationHours").value(12))
                .andExpect(jsonPath("$.data.photos[0].url").value("/api/uploads/stadiums/x/a.jpg"));
        Stadium stored = stadiumRepository.findById(stadium.getId()).orElseThrow();
        assertThat(stored.getMinBookingAmount()).isEqualTo(5_000_000L);
        assertThat(stored.getCancellationHours()).isEqualTo(12);
        assertThat(stored.getPhotos()).hasSize(1);
    }

    @Test
    void updateChecksOnlyWhatIsSentAndTheResultingHours() throws Exception {
        Stadium stadium = data.stadium(owner, "Update Arena"); // 08:00–23:00, no phone, no location
        update(stadium.getId(), "{\"name\":\"Renamed Arena\"}").andExpect(status().isOk());
        update(stadium.getId(), "{\"latitude\":41.3}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send both latitude and longitude"));
        update(stadium.getId(), "{\"latitude\":41.3,\"longitude\":69.2}").andExpect(status().isOk());
        update(stadium.getId(), "{\"closeTime\":\"07:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("closeTime must be after openTime; closing after midnight is not supported"));
        update(stadium.getId(), "{\"phone\":\"123\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("phone must be in the format +998XXXXXXXXX"));
        Stadium stored = stadiumRepository.findById(stadium.getId()).orElseThrow();
        assertThat(stored.getLatitude()).isEqualByComparingTo(new BigDecimal("41.3"));
        assertThat(stored.getCloseTime().getHour()).isEqualTo(23);
    }
}
