package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookingSeriesResponse {

    private UUID id;
    private UUID userId;
    private String userName;
    private UUID stadiumId;
    private String stadiumName;
    private Integer dayOfWeek;
    private LocalTime startTime;
    private Integer duration;

    @JsonProperty("isActive")
    private boolean isActive;

    private OffsetDateTime createdAt;

    /** Computed by the service: the next date this series will book. */
    private LocalDate nextBookingDate;
}
