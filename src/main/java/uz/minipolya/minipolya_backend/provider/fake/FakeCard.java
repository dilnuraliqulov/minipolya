package uz.minipolya.minipolya_backend.provider.fake;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A card bound at the fake, with a balance like a real card. Found by the SHA-256 of its
 * token ({@code tokenHash}): the token itself is never stored by the fake.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FakeCard {
    private String cardId;
    private String tokenHash;
    private String maskedPan;
    /** Decides the test scenario ("To simulate X in dev" in CLAUDE.md). */
    private String last4;
    /** MM/YY. */
    private String expiry;
    private String cardHolder;
    private String maskedPhone;
    /** Tiyin. */
    private long balance;
    private boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime removedAt;
}
