package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerDashboardStatsResponse {

    private UUID stadiumId;
    private String stadiumName;

    private int todayBookings;
    private long todayRevenue;

    private int weekBookings;
    private long weekRevenue;

    private int monthBookings;
    private long monthRevenue;

    // R38, tiyin. PaidInApp / PaidInCash: money that arrived for the stadium's bookings in the
    // period (card / cash), counted when it was paid. Outstanding: the remainingAmount of live
    // bookings starting in the period.
    private long todayPaidInApp;
    private long todayPaidInCash;
    private long todayOutstanding;
    private long weekPaidInApp;
    private long weekPaidInCash;
    private long weekOutstanding;
    private long monthPaidInApp;
    private long monthPaidInCash;
    private long monthOutstanding;

    private int totalBookings;
    private long totalRevenue;

    private double averageRating;
    private int reviewCount;
    private int occupancyPercent;
}
