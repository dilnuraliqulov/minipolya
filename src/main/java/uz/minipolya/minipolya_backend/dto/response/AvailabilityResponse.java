package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
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

        /** Tashkent wall clock. An hour after midnight reads 00:00/01:00 as it is shown. */
        private LocalTime startTime;
        private LocalTime endTime;

        /**
         * The real instant the hour starts, UTC. For an hour after midnight this is on the
         * calendar day after the working day the slot is listed under, which is exactly what
         * a booking of it stores.
         */
        private OffsetDateTime startsAt;

        /**
         * True when the hour falls after midnight, so it belongs to the working day that
         * opened the evening before. Absent otherwise.
         */
        private Boolean afterMidnight;
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
        /**
         * E3: the deposit a MINIMUM booking of this hour charges now (player tiyin), computed
         * exactly as {@code POST /api/bookings} charges it ({@code CardPricing.deposit}).
         * Present only on a free slot of a stadium with a minimum whose deposit is below the
         * slot's price; absent means full payment only.
         */
        private Long depositAmount;
        /** E3: what is left after the deposit ({@code pricePerHour - depositAmount}); absent with it. */
        private Long remainderAmount;
        private boolean hasWaitingGame;
        private UUID waitingGameId;
        private String waitingGameType;
        private Integer playersJoined;
        private Integer playersNeeded;
    }
}
