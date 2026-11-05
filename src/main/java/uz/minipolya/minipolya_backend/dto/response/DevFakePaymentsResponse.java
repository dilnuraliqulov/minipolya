package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * {@code GET /api/dev/fake-payments} (dev profile only): what the fake provider holds,
 * newest first. Amounts in tiyin. No token, card number or SMS code exists here to show.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevFakePaymentsResponse {

    private List<Card> cards;
    private List<Operation> charges;
    private List<Operation> holds;
    private List<Reverse> reverses;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Card {
        private String cardId;
        private String maskedPan;
        private String cardType;
        private String expiry;
        private String cardHolder;
        private String maskedPhone;
        /** What is left on the card. */
        private long balance;
        /** The test scenario the last four digits pick, e.g. "declined"; "success" for any other ending. */
        private String scenario;
        @JsonProperty("isActive")
        private boolean isActive;
        private OffsetDateTime createdAt;
        private OffsetDateTime removedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Operation {
        /** The hold id, or the charge's internal id. */
        private String id;
        /** Our payment row id. */
        private String account;
        private String cardId;
        private String maskedPan;
        /** PENDING, SUCCESS, FAILED, REVERSED, HELD, CAPTURED, RELEASED. */
        private String status;
        private String errorCode;
        private long amount;
        /** What ATMOS keeps of all splits together. */
        private long commission;
        private OffsetDateTime holdTill;
        private OffsetDateTime succeededAt;
        private OffsetDateTime createdAt;
        private OffsetDateTime updatedAt;
        private List<Split> splits;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Split {
        private String transactionId;
        /** "owner" or "platform". */
        private String recipient;
        /** The owner's ATMOS recipient id; absent for the platform's split. */
        private String recipientId;
        private long amount;
        private long commission;
        /** amount − commission. */
        private long recipientReceives;
        private String successTransId;
        private OffsetDateTime reversedAt;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Reverse {
        private String id;
        private String operationId;
        private String transactionId;
        /** Given back; 0 when refused. */
        private long amount;
        /** REVERSED or REFUSED. */
        private String outcome;
        private String errorCode;
        private String reason;
        private OffsetDateTime createdAt;
    }
}
