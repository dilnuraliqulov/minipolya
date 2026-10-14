package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminStadiumResponse {

    private UUID id;
    private String name;
    private String address;
    private String ownerName;
    private UUID ownerId;
    private String status;
    private String format;
    private String surfaceType;
    private double averageRating;
    private int reviewCount;
    private int totalBookings;
    private int totalRevenue;
    private OffsetDateTime createdAt;
}
