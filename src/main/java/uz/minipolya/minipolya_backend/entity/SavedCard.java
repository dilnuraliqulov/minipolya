package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * A card a player has bound with the payment provider.
 *
 * <p>Only {@code providerToken} and {@code maskedNumber} are kept: the card
 * number is passed straight to the provider during binding and is never stored
 * or logged. Deleting a card flips {@code isActive} rather than removing the
 * row, so a past payment can still be explained.
 */
@Entity
@Table(name = "saved_cards")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SavedCard {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The provider's handle for this card — what we charge. A secret: encrypted at rest
     * (AES-GCM, CARD_TOKEN_KEY), never logged, never in any API response.
     */
    @Convert(converter = CardTokenConverter.class)
    @Column(name = "provider_token", nullable = false, length = 512, unique = true)
    private String providerToken;

    /** Display only, e.g. {@code 8600 •••• 1234}. */
    @Column(name = "masked_number", nullable = false, length = 32)
    private String maskedNumber;

    /** {@code uzcard}, {@code humo} or {@code unknown}. */
    @Column(name = "card_type", length = 20)
    private String cardType;

    /** The provider's own id for the card, needed to remove it there. */
    @Column(name = "provider_card_id", length = 64)
    private String providerCardId;

    /** The expiry the provider reports, MM/YY — shown to the player. */
    @Column(length = 5)
    private String expiry;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
