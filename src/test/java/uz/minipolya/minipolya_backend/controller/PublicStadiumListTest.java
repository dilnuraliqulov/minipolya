package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Favorite;
import uz.minipolya.minipolya_backend.entity.Review;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumPhoto;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.FavoriteRepository;
import uz.minipolya.minipolya_backend.repository.ReviewRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.QueryCounter;
import uz.minipolya.minipolya_backend.support.TestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R23: the player stadium list (district, cover photo, favorite flag), the detail's rating. */
@DatabaseTest
class PublicStadiumListTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private QueryCounter queries;
    @Autowired
    private StadiumRepository stadiumRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private FavoriteRepository favoriteRepository;

    private User owner;

    @BeforeEach
    void setUp() {
        owner = data.owner("List Owner");
    }

    private Stadium stadium(String name, String district) {
        Stadium stadium = data.stadium(owner, name);
        stadium.setDistrict(district);
        return stadiumRepository.save(stadium);
    }

    @Test
    void theDistrictFilterKeepsOnlyThatDistrict() throws Exception {
        stadium("Zz Chilonzor", "Chilonzor");
        stadium("Zz Yunusobod", "Yunusobod");
        stadium("Zz Nowhere", null);

        mvc.perform(get("/api/stadiums").param("q", "Zz").param("district", "Chilonzor"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].name").value("Zz Chilonzor"))
                .andExpect(jsonPath("$.data.content[0].district").value("Chilonzor"));
        mvc.perform(get("/api/stadiums").param("q", "Zz"))
                .andExpect(jsonPath("$.data.content", hasSize(3)));
    }

    @Test
    void anUnknownDistrictIs400() throws Exception {
        mvc.perform(get("/api/stadiums").param("district", "Samarqand"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid district"));
    }

    @Test
    void onlyApprovedActiveStadiumsAreListed() throws Exception {
        stadium("Qq Visible", null);
        data.stadium(owner, "Qq Pending", "pending");
        Stadium inactive = stadium("Qq Inactive", null);
        inactive.setActive(false);
        stadiumRepository.save(inactive);

        mvc.perform(get("/api/stadiums").param("q", "Qq"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].name").value("Qq Visible"));
    }

    @Test
    void aCardHasTheFirstPhotoAndTheFavoriteFlagOnlyForASignedInCaller() throws Exception {
        Stadium withPhotos = stadium("Ww Photos", null);
        withPhotos.addPhoto(StadiumPhoto.builder().url("https://cdn/second.jpg").position(1).build());
        withPhotos.addPhoto(StadiumPhoto.builder().url("https://cdn/first.jpg").position(0).build());
        stadiumRepository.save(withPhotos);
        stadium("Ww Bare", null);
        User player = data.user("Fan");
        favoriteRepository.save(Favorite.builder().user(player).stadium(withPhotos).build());

        mvc.perform(get("/api/stadiums").param("q", "Ww"))
                .andExpect(jsonPath("$.data.content[0].coverPhotoUrl").value("https://cdn/first.jpg"))
                .andExpect(jsonPath("$.data.content[0].isFavorited").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].coverPhotoUrl").doesNotExist());
        mvc.perform(get("/api/stadiums").param("q", "Ww").header("Authorization", data.bearer(player)))
                .andExpect(jsonPath("$.data.content[0].isFavorited").value(true))
                .andExpect(jsonPath("$.data.content[1].isFavorited").value(false));

        // The favorites list is the same card.
        mvc.perform(get("/api/favorites").header("Authorization", data.bearer(player)))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].coverPhotoUrl").value("https://cdn/first.jpg"))
                .andExpect(jsonPath("$.data.content[0].isFavorited").value(true))
                .andExpect(jsonPath("$.data.content[0].pricePerHour").value(20_500_000));
    }

    @Test
    void theDetailCarriesTheRating() throws Exception {
        Stadium stadium = stadium("Rated", null);
        reviewRepository.save(Review.builder().stadium(stadium).user(data.user("R1")).rating(5).build());
        reviewRepository.save(Review.builder().stadium(stadium).user(data.user("R2")).rating(4).build());
        Stadium unrated = stadium("Unrated", null);

        mvc.perform(get("/api/stadiums/" + stadium.getId()))
                .andExpect(jsonPath("$.data.averageRating").value(4.5))
                .andExpect(jsonPath("$.data.reviewCount").value(2));
        mvc.perform(get("/api/stadiums/" + unrated.getId()))
                .andExpect(jsonPath("$.data.averageRating").doesNotExist())
                .andExpect(jsonPath("$.data.reviewCount").value(0));
    }

    @Test
    void aPageOfStadiumsCostsAFixedNumberOfQueries() throws Exception {
        User reviewer = data.user("Bulk Reviewer");
        User player = data.user("Bulk Fan");
        for (int i = 0; i < 22; i++) {
            Stadium stadium = stadium("Bulk " + i, "Chilonzor");
            stadium.addPhoto(StadiumPhoto.builder().url("https://cdn/" + i + ".jpg").position(0).build());
            stadiumRepository.save(stadium);
            reviewRepository.save(Review.builder().stadium(stadium).user(reviewer).rating(4).build());
            favoriteRepository.save(Favorite.builder().user(player).stadium(stadium).build());
        }
        String token = data.bearer(player);
        queries.start();

        mvc.perform(get("/api/stadiums").param("q", "Bulk").param("district", "Chilonzor").param("size", "50")
                        .header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(22)))
                .andExpect(jsonPath("$.data.content[21].coverPhotoUrl").value("https://cdn/21.jpg"))
                .andExpect(jsonPath("$.data.content[21].averageRating").value(4.0))
                .andExpect(jsonPath("$.data.content[21].isFavorited").value(true));

        // auth user, settings, stadiums (+owner +amenities), count, ratings, photos, favorites
        assertThat(queries.count()).isLessThanOrEqualTo(8);

        queries.start();
        mvc.perform(get("/api/favorites").param("size", "50").header("Authorization", token))
                .andExpect(jsonPath("$.data.content", hasSize(22)));
        // auth user, favorites (+stadium +owner +amenities), count, settings, ratings, photos, favorites
        assertThat(queries.count()).isLessThanOrEqualTo(8);
    }
}
