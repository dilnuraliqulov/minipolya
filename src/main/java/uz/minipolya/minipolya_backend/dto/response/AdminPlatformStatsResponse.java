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
    private long newUsersThisWeek;
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

    /** Refunds the provider declined that still wait for an admin (retry or mark refunded). */
    private long failedRefunds;

    /** When the oldest of those was written (UTC); omitted when there are none. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private OffsetDateTime oldestFailedRefundAt;
}
