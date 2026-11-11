package uz.minipolya.minipolya_backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R29: GET /api/bookings?when=upcoming|past — the player's bookings split, each in its own order. */
@DatabaseTest
class MyBookingsWhenFilterTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;

    private User player;
    private Booking lastWeek;
    private Booking yesterday;
    private Booking inAnHour;
    private Booking tomorrow;
    private Booking nextWeek;

    @BeforeEach
    void setUp() {
        player = data.user("When Player");
        Stadium stadium = data.stadium(data.owner("When Owner"), "When Arena");
        OffsetDateTime hour = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
        lastWeek = data.booking(stadium, player, hour.minusDays(7), "completed");
        yesterday = data.booking(stadium, player, hour.minusDays(1), "completed");
        inAnHour = data.booking(stadium, player, hour.plusHours(1), "confirmed");
        tomorrow = data.booking(stadium, player, hour.plusDays(1), "pending");
        nextWeek = data.booking(stadium, player, hour.plusDays(7), "cancelled_by_user");
    }

    private List<String> ids(String query) throws Exception {
        String body = mvc.perform(get("/api/bookings" + query).header("Authorization", data.bearer(player)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.content[*].id");
    }

    private static List<String> idsOf(Booking... bookings) {
        return java.util.Arrays.stream(bookings).map(b -> b.getId().toString()).toList();
    }

    @Test
    void upcomingIsWhatHasNotStartedYetSoonestFirst() throws Exception {
        assertThat(ids("?when=upcoming")).containsExactlyElementsOf(idsOf(inAnHour, tomorrow, nextWeek));
    }

    @Test
    void pastIsWhatHasStartedNewestFirst() throws Exception {
        assertThat(ids("?when=past")).containsExactlyElementsOf(idsOf(yesterday, lastWeek));
    }

    @Test
    void withoutWhenItIsEverythingAsBeforeLatestStartFirst() throws Exception {
        assertThat(ids("")).containsExactlyElementsOf(idsOf(nextWeek, tomorrow, inAnHour, yesterday, lastWeek));
    }

    @Test
    void thePagesFollowTheOrder() throws Exception {
        assertThat(ids("?when=upcoming&size=2&page=0")).containsExactlyElementsOf(idsOf(inAnHour, tomorrow));
        assertThat(ids("?when=upcoming&size=2&page=1")).containsExactlyElementsOf(idsOf(nextWeek));
        mvc.perform(get("/api/bookings?when=upcoming&size=2").header("Authorization", data.bearer(player)))
                .andExpect(jsonPath("$.data.totalElements").value(3));
    }

    @Test
    void anyOtherValueIs400() throws Exception {
        mvc.perform(get("/api/bookings?when=soon").header("Authorization", data.bearer(player)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid when"));
    }
}
