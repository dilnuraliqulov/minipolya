package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A saved card as the player sees it. The provider token is deliberately absent:
 * the client picks a card by {@code id}, never by token.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavedCardResponse {

    private UUID id;
    private String maskedNumber;
    private String cardType;
    /** MM/YY, as the provider reports it. */
    private String expiry;

    @JsonProperty("isActive")
    private boolean isActive;

    private OffsetDateTime createdAt;
}
