package uz.minipolya.minipolya_backend.provider.fake;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import uz.minipolya.minipolya_backend.provider.ProviderStatus;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * One charge or hold at the fake: the whole request, with one {@link FakeSplit} per
 * recipient (ATMOS makes one transaction per split). {@code account} is our payment row
 * id; the same account never makes a second operation of the same kind.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FakeOperation {

    public enum Kind { CHARGE, HOLD }

    /** The hold id; for a charge, an internal id (the provider hands out the split transaction ids). */
    private String id;
    private Kind kind;
    private String account;
    /** Null for a charge made with a card number instead of a saved card. */
    private String cardId;
    private String last4;
    private boolean otpRequired;
    /** Tiyin, the sum of the splits. */
    private long amount;
    /** Tiyin ATMOS keeps of all splits together. */
    private long commission;
    private ProviderStatus status;
    /** Why it failed or was let go, as an ATMOS code. */
    private String errorCode;
    /** A hold's end. */
    private OffsetDateTime holdTill;
    /** When the money was taken (a charge confirmed, a hold captured). */
    private OffsetDateTime succeededAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    @Builder.Default
    private List<FakeSplit> splits = new ArrayList<>();

    public boolean allReversed() {
        return !splits.isEmpty() && splits.stream().allMatch(s -> s.getReversedAt() != null);
    }
}
