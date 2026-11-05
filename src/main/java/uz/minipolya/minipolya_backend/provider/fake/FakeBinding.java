package uz.minipolya.minipolya_backend.provider.fake;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A card binding the fake has started and is waiting for its SMS code. Only what ATMOS
 * would show back is kept — the masked number, its last four digits and the expiry —
 * never the card number itself.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FakeBinding {
    private String bindingId;
    private String maskedPan;
    private String last4;
    /** MM/YY. */
    private String expiry;
    private String maskedPhone;
    private String cardHolder;
    /** When the current code was sent: its lifetime and the resend cooldown run from here. */
    private OffsetDateTime codeSentAt;
    private OffsetDateTime confirmedAt;
    private OffsetDateTime createdAt;
}
