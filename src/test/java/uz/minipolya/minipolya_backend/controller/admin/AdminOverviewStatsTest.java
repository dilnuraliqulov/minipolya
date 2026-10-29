package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;
import uz.minipolya.minipolya_backend.util.TashkentDays;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R4, R5, R3: the overview's chart and comparison figures. */
@DatabaseTest
class AdminOverviewStatsTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private Clock clock;
    @Autowired
    private UserRepository userRepository;

    private String adminToken;
    private User player;
    private Stadium stadium;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        adminToken = data.bearer(data.admin("Overview Admin"));
        player = data.user("Overview Player");
        stadium = data.stadium(data.owner("Overview Owner"), "Overview Arena");
        today = TashkentDays.today(clock);
    }

    /** {@code hour}:00 Tashkent on that day. */
    private OffsetDateTime at(LocalDate day, int hour) {
        return TashkentDays.startOf(day).plusHours(hour);
    }

    private void userCreatedDaysAgo(String name, int days) {
        userRepository.save(User.builder().name(name).createdAt(OffsetDateTime.now(clock).minusDays(days)).build());
    }

    @Test
    void newUsersAreCountedOverTheLastSevenDaysWhateverTheWeekday() throws Exception {
        userCreatedDaysAgo("Three days", 3);
        userCreatedDaysAgo("Six days", 6);
        userCreatedDaysAgo("Eight days", 8);

        // The admin, the player and the owner of setUp are new too.
        mvc.perform(get("/api/admin/stats").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.newUsersLast7Days").value(5))
                .andExpect(jsonPath("$.data.newUsersThisWeek").exists());
    }

    @Test
    void theChartEndpointIsForAdminsOnly() throws Exception {
        mvc.perform(get("/api/admin/stats/bookings-daily")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/stats/bookings-daily").header("Authorization", data.bearer(player)))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyOneOfTheLast30DaysIsPresentWithItsBookings() throws Exception {
        // Today: one at Tashkent midnight (still yesterday in UTC), one in the evening.
        data.booking(stadium, player, at(today, 0), "confirmed");
        data.booking(stadium, player, at(today, 20), "completed");
        // Not counted: still pending, or cancelled.
        data.booking(stadium, player, at(today, 21), "pending");
        data.booking(stadium, player, at(today, 22), "cancelled_by_user");
        // Five days ago, and one just outside the window.
        data.booking(stadium, player, at(today.minusDays(5), 10), "completed");
        data.booking(stadium, player, at(today.minusDays(30), 10), "completed");

        mvc.perform(get("/api/admin/stats/bookings-daily").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(30)))
                .andExpect(jsonPath("$.data[0].date").value(today.minusDays(29).toString()))
                .andExpect(jsonPath("$.data[29].date").value(today.toString()))
                .andExpect(jsonPath("$.data[29].bookingCount").value(2))
                .andExpect(jsonPath("$.data[24].date").value(today.minusDays(5).toString()))
                .andExpect(jsonPath("$.data[24].bookingCount").value(1))
                .andExpect(jsonPath("$.data[28].bookingCount").value(0));

        // The stats agree with the series; the revenue chart carries the same count.
        mvc.perform(get("/api/admin/stats").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.todayBookings").value(2));
        mvc.perform(get("/api/admin/stats/revenue").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.dailyRevenue[29].bookingCount").value(2));
    }
}
