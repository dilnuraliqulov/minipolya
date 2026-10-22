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
    private int todayRevenue;

    private int weekBookings;
    private int weekRevenue;

    private int monthBookings;
    private int monthRevenue;

    private int totalBookings;
    private int totalRevenue;

    private double averageRating;
    private int reviewCount;
    private int occupancyPercent;
}
