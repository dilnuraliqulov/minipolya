package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PromotionResponse {

    private UUID id;
    private UUID stadiumId;
    private String stadiumName;
    private String name;
    private String description;
    private Integer discountPercent;
    private OffsetDateTime startsAt;
    private OffsetDateTime endsAt;
    private Integer dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;

    @JsonProperty("isActive")
    private boolean isActive;

    private OffsetDateTime createdAt;
}
