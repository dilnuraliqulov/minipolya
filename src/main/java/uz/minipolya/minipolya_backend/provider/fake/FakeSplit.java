package uz.minipolya.minipolya_backend.provider.fake;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** One recipient's transaction within a {@link FakeOperation}. Amounts in tiyin. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FakeSplit {
    private String transactionId;
    private int position;
    /** The owner's ATMOS recipient; null for the platform's split. */
    private String recipientId;
    private boolean platform;
    private long amount;
    /** What ATMOS keeps of this split: 1.5%, rounded up. */
    private long commission;
    /** Set once the money is taken; what a reverse names. */
    private String successTransId;
    private OffsetDateTime reversedAt;
}
