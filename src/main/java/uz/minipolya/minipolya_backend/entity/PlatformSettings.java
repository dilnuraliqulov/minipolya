package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * Platform-wide money settings, edited by admins. Exactly one row exists —
 * {@code singleton} is UNIQUE and CHECKed true, so the database rejects a
 * second one. All fees are tiyin; {@code bookingCommissionPercent} is a
 * percentage of the booking price kept by the platform.
 */
@Entity
@Table(name = "platform_settings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlatformSettings {

    /** Default booking commission: the platform takes nothing for now. */
    public static final int DEFAULT_BOOKING_COMMISSION_PERCENT = 0;

    /** Default team-game platform fee: 2 500 000 tiyin = 25 000 so'm per team. */
    public static final long DEFAULT_TEAM_GAME_PLATFORM_FEE = 2_500_000L;

    /** Default casual-game service fee per player: nothing for now. */
    public static final long DEFAULT_CASUAL_GAME_SERVICE_FEE = 0L;

    /** The highest commission an admin may set. */
    public static final int MAX_BOOKING_COMMISSION_PERCENT = 50;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Always true; UNIQUE + CHECK make this the single-row guard. */
    @Builder.Default
    @Column(nullable = false)
    private boolean singleton = true;

    @Builder.Default
    @Column(name = "booking_commission_percent", nullable = false)
    private int bookingCommissionPercent = DEFAULT_BOOKING_COMMISSION_PERCENT;

    @Builder.Default
    @Column(name = "team_game_platform_fee", nullable = false)
    private long teamGamePlatformFee = DEFAULT_TEAM_GAME_PLATFORM_FEE;

    @Builder.Default
    @Column(name = "casual_game_service_fee", nullable = false)
    private long casualGameServiceFee = DEFAULT_CASUAL_GAME_SERVICE_FEE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
