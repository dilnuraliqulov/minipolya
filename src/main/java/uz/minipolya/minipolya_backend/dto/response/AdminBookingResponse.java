package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * A booking as the admin panel sees it: every {@link BookingResponse} field in the
 * owner's view (card {@code price}, {@code netPrice}, {@code cardFee}, ...) at the top
 * level, plus who booked it and how the money came in. Absent fields are omitted.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminBookingResponse {

    @JsonUnwrapped
    private BookingResponse booking;

    /** The booking user; absent on a walk-in, whose name is {@code clientName}. */
    private String userName;
    private String userPhone;

    /**
     * Tiyin: card money kept on this booking — gross, card fee included — from payments
     * that arrived ({@code paid}, {@code captured}) and were not given back.
     */
    private Long paidInApp;

    /** Tiyin of cash the owner marked as received; absent when none. */
    private Long cashReceived;

    /** When the owner last marked cash as received (UTC). */
    private OffsetDateTime cashReceivedAt;
}
