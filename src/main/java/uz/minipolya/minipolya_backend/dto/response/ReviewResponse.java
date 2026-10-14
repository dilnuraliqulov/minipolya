package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A stadium review as returned to clients. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ReviewResponse {

    private UUID id;
    private UUID stadiumId;
    private String stadiumName;
    private UUID userId;
    private String userName;
    private UUID bookingId;
    private Integer rating;
    private String comment;
    private String ownerReply;
    private OffsetDateTime ownerReplyAt;
    private OffsetDateTime createdAt;
}
