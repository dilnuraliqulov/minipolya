package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Starts binding a card. The number is forwarded to the payment provider and
 * never stored or logged, so {@code toString} is suppressed to keep it out of
 * accidental log lines.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddCardRequest {

    @NotBlank
    @Pattern(regexp = "\\d{16}", message = "cardNumber must be 16 digits")
    private String cardNumber;

    @NotBlank
    @Pattern(regexp = "\\d{2}/\\d{2}", message = "expiry must be MM/YY")
    private String expiry;

    /** Never render the card number or the expiry. */
    @Override
    public String toString() {
        return "AddCardRequest(cardNumber=****, expiry=**/**)";
    }
}
