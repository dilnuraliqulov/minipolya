package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminPlatformStatsResponse {

    private long totalUsers;
    private long totalOwners;
    private long newUsersToday;
    /** Since Monday 00:00 Tashkent. */
    private long newUsersThisWeek;
    /** In the last 7 × 24 hours, whatever the weekday. */
    private long newUsersLast7Days;
    private long newUsersThisMonth;

    private long totalStadiums;
    private long approvedStadiums;
    private long pendingStadiums;
    private long blockedStadiums;
    private long rejectedStadiums;

    private long totalBookings;
    private long todayBookings;
    private long weekBookings;
    private long monthBookings;

    private long totalRevenue;
    private long todayRevenue;
    private long weekRevenue;
    private long monthRevenue;

    private long totalGames;
    private long activeGames;

    private long pendingOwnerApplications;
    private long pendingStadiumApprovals;

    // ---- The previous period, for the overview's comparison lines (the client works out the %) ----

    /** Bookings starting on the same weekday last week, counted like todayBookings. */
    private long todayBookingsLastWeek;
    /** Tiyin: last month from the 1st to the same day of the month, counted like monthRevenue. */
    private long previousMonthRevenueToDate;
    /** Users created 7 to 14 days ago — the period before newUsersLast7Days. */
    private long newUsersPrevious7Days;
    /** Stadiums that were approved 7 days ago. */
    private long approvedStadiumsWeekAgo;

    /** Refunds the provider declined that still wait for an admin (retry or mark refunded). */
    private long failedRefunds;

    /** When the oldest of those was written (UTC); omitted when there are none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OffsetDateTime oldestFailedRefundAt;
}
