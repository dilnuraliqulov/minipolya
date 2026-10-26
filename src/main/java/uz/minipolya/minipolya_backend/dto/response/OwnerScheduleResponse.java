package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerScheduleResponse {

    private LocalDate date;
    private List<BookingResponse> bookings;

    /**
     * Slots nobody has booked but a team is waiting on: a team-vs-team game
     * looking for an opponent. The pitch is still free — the owner can take a
     * walk-in for it — but they should know somebody is already after it.
     */
    private List<WaitingGame> waitingGames;
    private int totalSlots;
    private int bookedSlots;
    private long revenue;

    /** One team-vs-team game on this day that is still looking for an opponent. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WaitingGame {
        private UUID gameId;
        private String teamName;
        private OffsetDateTime startsAt;
        private OffsetDateTime endsAt;
    }
}
