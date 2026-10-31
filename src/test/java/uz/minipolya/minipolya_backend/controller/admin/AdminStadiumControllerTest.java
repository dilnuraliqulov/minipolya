package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.dto.request.AmenitiesRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateStadiumRequest;
import uz.minipolya.minipolya_backend.dto.response.StadiumDetailResponse;
import uz.minipolya.minipolya_backend.entity.PricingRule;
import uz.minipolya.minipolya_backend.entity.Review;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.service.StadiumService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.QueryCounter;
import uz.minipolya.minipolya_backend.support.TestData;
import uz.minipolya.minipolya_backend.util.Districts;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R9: everything an admin reviews, for a stadium in any status; and the district. */
@DatabaseTest
class AdminStadiumControllerTest {

    private static final String MIRZO_ULUGBEK = "Mirzo Ulugʻbek";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private QueryCounter queries;
    @Autowired
    private StadiumService stadiumService;
    @Autowired
    private StadiumRepository stadiumRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private PricingRuleRepository pricingRuleRepository;

    private String adminToken;
    private User owner;

    @BeforeEach
    void setUp() {
        adminToken = data.bearer(data.admin("Stadium Admin"));
        owner = data.owner("Stadium Owner");
    }

    private CreateStadiumRequest request(String name, String district) {
        return CreateStadiumRequest.builder()
                .name(name).address("Toshkent, " + name).district(district)
                .format("7x7").surfaceType("artificial")
                .openTime(LocalTime.of(8, 0)).closeTime(LocalTime.of(23, 0))
                .pricePerHour(20_000_000L).minBookingAmount(10_000_000L).cancellationHours(12)
                .phone("+998901112233").description("Yopiq maydon")
                .photos(List.of("https://cdn/a.jpg", "https://cdn/b.jpg"))
                .amenities(AmenitiesRequest.builder().parking(true).showers(true).build())
                .build();
    }

    // ---- Access ----

    @Test
    void withoutATokenItIs401AndANonAdminGets403() throws Exception {
        mvc.perform(get("/api/admin/stadiums")).andExpect(status().isUnauthorized());
        String player = data.bearer(data.user("Stadium Player"));
        mvc.perform(get("/api/admin/stadiums").header("Authorization", player)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/stadiums/" + UUID.randomUUID()).header("Authorization", player))
                .andExpect(status().isForbidden());
    }

    // ---- District ----

    @Test
    void anOwnerPicksADistrictFromTheTwelve() {
        StadiumDetailResponse created = stadiumService.create(owner.getId(), request("Ulugbek Arena", MIRZO_ULUGBEK));

        // Saved and read back through the CHECK constraint: the Java list and the SQL agree.
        assertThat(stadiumRepository.findById(created.getId()).orElseThrow().getDistrict()).isEqualTo(MIRZO_ULUGBEK);
        assertThat(created.getDistrict()).isEqualTo(MIRZO_ULUGBEK);

        UpdateStadiumRequest move = UpdateStadiumRequest.builder().district("Chilonzor").build();
        assertThat(stadiumService.update(owner.getId(), created.getId(), move).getDistrict()).isEqualTo("Chilonzor");
        // Not sent: left as it is.
        UpdateStadiumRequest rename = UpdateStadiumRequest.builder().name("Renamed").build();
        assertThat(stadiumService.update(owner.getId(), created.getId(), rename).getDistrict()).isEqualTo("Chilonzor");
    }

    @Test
    void everyDistrictPassesTheDatabaseConstraint() {
        for (String district : Districts.ALL) {
            Stadium stadium = data.stadium(owner, "D " + district);
            stadium.setDistrict(district);
            stadiumRepository.saveAndFlush(stadium);
        }
    }

    @Test
    void aDistrictOutsideTheListIsRefused() {
        assertThatThrownBy(() -> stadiumService.create(owner.getId(), request("Bad", "Mirzo Ulug'bek")))
                .isInstanceOf(BadRequestException.class).hasMessage("Invalid district");
        StadiumDetailResponse created = stadiumService.create(owner.getId(), request("Good", null));
        assertThatThrownBy(() -> stadiumService.update(owner.getId(), created.getId(),
                UpdateStadiumRequest.builder().district("Samarqand").build()))
                .isInstanceOf(BadRequestException.class).hasMessage("Invalid district");
    }

    // ---- List ----

    @Test
    void aPageOfStadiumsCostsAFixedNumberOfQueries() throws Exception {
        User reviewer = data.user("Reviewer");
        for (int i = 0; i < 22; i++) {
            Stadium stadium = data.stadium(owner, "Bulk " + i, i % 2 == 0 ? "pending" : "approved");
            stadium.addPhoto(StadiumPhoto.builder().url("https://cdn/" + i + "-second.jpg").position(1).build());
            stadium.addPhoto(StadiumPhoto.builder().url("https://cdn/" + i + "-first.jpg").position(0).build());
            stadiumRepository.save(stadium);
            reviewRepository.save(Review.builder().stadium(stadium).user(reviewer).rating(4).build());
        }
        queries.start();

        mvc.perform(get("/api/admin/stadiums").header("Authorization", adminToken)
                        .param("search", "Bulk").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(22)))
                .andExpect(jsonPath("$.data.content[0].photoUrl").value("https://cdn/21-first.jpg"))
                .andExpect(jsonPath("$.data.content[0].reviewCount").value(1))
                .andExpect(jsonPath("$.data.content[0].averageRating").value(4.0));

        // auth user, stadiums (+owner +amenities), count, ratings, bookings, revenue, photos, settings
        assertThat(queries.count()).isLessThanOrEqualTo(9);
    }

    @Test
    void theListFiltersByDistrictAndFormatAndShowsBothPrices() throws Exception {
        stadiumService.create(owner.getId(), request("Chilonzor Arena", "Chilonzor"));
        stadiumService.create(owner.getId(), request("Yunusobod Arena", "Yunusobod"));
        data.stadium(owner, "Old Arena"); // 5x5, no district

        mvc.perform(get("/api/admin/stadiums").header("Authorization", adminToken).param("district", "Chilonzor"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].name").value("Chilonzor Arena"))
                .andExpect(jsonPath("$.data.content[0].district").value("Chilonzor"))
                .andExpect(jsonPath("$.data.content[0].status").value("pending"))
                .andExpect(jsonPath("$.data.content[0].pricePerHour").value(20_000_000))
                .andExpect(jsonPath("$.data.content[0].price").value(20_310_000))
                .andExpect(jsonPath("$.data.content[0].photoUrl").value("https://cdn/a.jpg"));
        mvc.perform(get("/api/admin/stadiums").header("Authorization", adminToken)
                        .param("format", "7x7").param("search", "Arena"))
                .andExpect(jsonPath("$.data.content", hasSize(2)));
        mvc.perform(get("/api/admin/stadiums").header("Authorization", adminToken).param("search", "Old Arena"))
                .andExpect(jsonPath("$.data.content[0].district").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].photoUrl").doesNotExist());
    }

    @Test
    void unknownFilterValuesAre400() throws Exception {
        mvc.perform(get("/api/admin/stadiums").header("Authorization", adminToken).param("district", "Nowhere"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid district"));
        mvc.perform(get("/api/admin/stadiums").header("Authorization", adminToken).param("format", "6x6"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid format"));
    }

    // ---- Detail ----

    @Test
    void aPendingStadiumCanBeReviewedInFull() throws Exception {
        StadiumDetailResponse created = stadiumService.create(owner.getId(), request("Review Me", "Sergeli"));
        Stadium stadium = stadiumRepository.findById(created.getId()).orElseThrow();
        pricingRuleRepository.save(PricingRule.builder().stadium(stadium).dayOfWeek(5)
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(23, 0)).pricePerHour(30_000_000L).build());

        // Players cannot see it yet...
        mvc.perform(get("/api/stadiums/" + created.getId())).andExpect(status().isNotFound());

        // ...the admin sees everything players will.
        mvc.perform(get("/api/admin/stadiums/" + created.getId()).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("pending"))
                .andExpect(jsonPath("$.data.district").value("Sergeli"))
                .andExpect(jsonPath("$.data.ownerPhone").value(owner.getPhone()))
                .andExpect(jsonPath("$.data.phone").value("+998901112233"))
                .andExpect(jsonPath("$.data.description").value("Yopiq maydon"))
                .andExpect(jsonPath("$.data.openTime").value("08:00:00"))
                .andExpect(jsonPath("$.data.closeTime").value("23:00:00"))
                .andExpect(jsonPath("$.data.photos", hasSize(2)))
                .andExpect(jsonPath("$.data.photos[0].url").value("https://cdn/a.jpg"))
                .andExpect(jsonPath("$.data.photoUrl").value("https://cdn/a.jpg"))
                .andExpect(jsonPath("$.data.amenities.parking").value(true))
                .andExpect(jsonPath("$.data.amenities.wifi").value(false))
                .andExpect(jsonPath("$.data.pricePerHour").value(20_000_000))
                .andExpect(jsonPath("$.data.netPrice").value(20_000_000))
                .andExpect(jsonPath("$.data.price").value(20_310_000))
                .andExpect(jsonPath("$.data.minBookingAmount").value(10_000_000))
                .andExpect(jsonPath("$.data.cancellationHours").value(12))
                .andExpect(jsonPath("$.data.pricingRules", hasSize(1)))
                .andExpect(jsonPath("$.data.pricingRules[0].dayOfWeek").value(5))
                .andExpect(jsonPath("$.data.pricingRules[0].netPrice").value(30_000_000))
                .andExpect(jsonPath("$.data.pricingRules[0].price").value(30_460_000))
                .andExpect(jsonPath("$.data.totalBookings").value(0))
                .andExpect(jsonPath("$.data.totalRevenue").value(0))
                .andExpect(jsonPath("$.data.stadium").doesNotExist());

        mvc.perform(get("/api/admin/stadiums/" + UUID.randomUUID()).header("Authorization", adminToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("Stadium not found"));
    }
}
