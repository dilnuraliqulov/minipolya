package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/**
 * Every 1-hour slot of a stadium's opening hours on a date, each with its status
 * (free / booked / closed / past), and for a free one its price and game info.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AvailabilityResponse {

    private UUID stadiumId;
    private String stadiumName;
    private LocalDate date;
    private List<TimeSlot> slots;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class TimeSlot {

        public static final String FREE = "free";
        public static final String BOOKED = "booked";
        public static final String CLOSED = "closed";
        public static final String PAST = "past";

        private LocalTime startTime;
        private LocalTime endTime;
        /** True exactly when {@code status} is {@code free}. */
        private boolean available;
        /**
         * {@code free}; {@code booked} (a pending or confirmed booking holds it);
         * {@code closed} (a stadium closure); {@code past} (today, the hour has started).
         * When several apply: past, then booked, then closed.
         */
        private String status;
        private Long pricePerHour;      // the one price the player pays for this hour; null if not available
        private Long originalPrice;     // the player price before the discount; null if no promotion
        private Integer discountPercent; // null if no promotion
        private String promotionName;    // null if no promotion
        private boolean hasWaitingGame;
        private UUID waitingGameId;
        private String waitingGameType;
        private Integer playersJoined;
        private Integer playersNeeded;
    }
}
