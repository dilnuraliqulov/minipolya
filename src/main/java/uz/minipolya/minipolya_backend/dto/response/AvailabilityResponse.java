package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** Free/occupied 1-hour slots for a stadium on a date, with price and game info. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AvailabilityResponse {

    private Long stadiumId;
    private String stadiumName;
    private LocalDate date;
    private List<TimeSlot> slots;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TimeSlot {
        private LocalTime startTime;
        private LocalTime endTime;
        private boolean available;
        private Integer pricePerHour;    // null if not available
        private Integer originalPrice;   // before discount, null if no promotion
        private Integer discountPercent; // null if no promotion
        private String promotionName;    // null if no promotion
        private boolean hasWaitingGame;
        private Long waitingGameId;
        private String waitingGameType;
        private Integer playersJoined;
        private Integer playersNeeded;
    }
}
