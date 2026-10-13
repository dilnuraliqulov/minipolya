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
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminStadiumResponse {

    private Long id;
    private String name;
    private String address;
    private String ownerName;
    private Long ownerId;
    private String status;
    private String format;
    private String surfaceType;
    private double averageRating;
    private int reviewCount;
    private int totalBookings;
    private int totalRevenue;
    private OffsetDateTime createdAt;
}
