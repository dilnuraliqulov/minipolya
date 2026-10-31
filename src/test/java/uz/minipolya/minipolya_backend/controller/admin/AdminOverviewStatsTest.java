package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.AuditLog;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.AuditLogRepository;
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
    @Autowired
    private AuditLogRepository auditLogRepository;

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

    /** A card payment taken at {@code when}, ATMOS's fee reported: it counts in full as kept. */
    private void paidOn(Booking booking, long amount, OffsetDateTime when) {
        data.save(Payment.builder()
                .booking(booking).user(booking.getUser())
                .amount(amount).ownerAmount(amount).platformAmount(0L)
                .cardFeeExpected(0L).providerCommission(0L).commissionPercent(0)
                .method(Payment.Method.CARD).paymentType(Payment.PaymentType.BOOKING)
                .provider(Payment.Provider.ATMOS).providerTxnId("txn-" + java.util.UUID.randomUUID())
                .status(Payment.Status.PAID).createdAt(when)
                .build());
    }

    private void statusChanged(Stadium stadium, String action, String from, String to, int daysAgo) {
        auditLogRepository.save(AuditLog.builder()
                .actorType("ADMIN").action(action).entityType("stadium").entityId(stadium.getId())
                .changes("[{\"field\":\"status\",\"oldValue\":\"" + from + "\",\"newValue\":\"" + to + "\"}]")
                .createdAt(OffsetDateTime.now(clock).minusDays(daysAgo))
                .build());
    }

    @Test
    void thePreviousPeriodsAreReturnedAsValues() throws Exception {
        // Same weekday last week: one counted, the next day's not.
        data.booking(stadium, player, at(today.minusDays(7), 10), "completed");
        data.booking(stadium, player, at(today.minusDays(6), 0), "completed");

        // Last month: the 1st is inside the to-date stretch, the day after it is not.
        LocalDate lastMonthFirst = today.withDayOfMonth(1).minusMonths(1);
        LocalDate firstDayOutside = lastMonthFirst.plusDays(
                Math.min(today.getDayOfMonth(), lastMonthFirst.lengthOfMonth()));
        Booking old = data.booking(stadium, player, at(today.minusDays(40), 10), "completed");
        paidOn(old, 20_310_000L, at(lastMonthFirst, 12));
        paidOn(old, 10_160_000L, at(firstDayOutside, 12));

        // Users 7–14 days old.
        userCreatedDaysAgo("Eight days", 8);
        userCreatedDaysAgo("Thirteen days", 13);
        userCreatedDaysAgo("Three days", 3);
        userCreatedDaysAgo("Fifteen days", 15);

        // Approved now: Overview Arena, and two approved this week. One was blocked this week.
        Stadium approvedTwoDaysAgo = data.stadium(stadium.getOwner(), "Approved 2d ago", "approved");
        statusChanged(approvedTwoDaysAgo, "STADIUM_APPROVED", "pending", "approved", 2);
        Stadium approvedYesterday = data.stadium(stadium.getOwner(), "Approved 1d ago", "approved");
        statusChanged(approvedYesterday, "STADIUM_APPROVED", "pending", "approved", 1);
        Stadium blockedThisWeek = data.stadium(stadium.getOwner(), "Blocked 3d ago", "blocked");
        statusChanged(blockedThisWeek, "STADIUM_APPROVED", "pending", "approved", 20);
        statusChanged(blockedThisWeek, "STADIUM_BLOCKED", "approved", "blocked", 3);

        mvc.perform(get("/api/admin/stats").header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.todayBookingsLastWeek").value(1))
                .andExpect(jsonPath("$.data.previousMonthRevenueToDate").value(20_310_000))
                .andExpect(jsonPath("$.data.newUsersPrevious7Days").value(2))
                .andExpect(jsonPath("$.data.approvedStadiums").value(3))
                // 3 now − 2 approved since + 1 blocked since
                .andExpect(jsonPath("$.data.approvedStadiumsWeekAgo").value(2));
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
