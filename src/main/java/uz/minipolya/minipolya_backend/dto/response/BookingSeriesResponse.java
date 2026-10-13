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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BookingSeriesResponse {

    private Long id;
    private Long userId;
    private String userName;
    private Long stadiumId;
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
