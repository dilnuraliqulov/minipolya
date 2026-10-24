package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * A reserved slot on a stadium. Overlapping active bookings (pending or
 * confirmed) are rejected by the {@code bookings_no_overlap} EXCLUDE
 * constraint, so app, manual and game bookings all share one conflict-free
 * schedule. All money is integer so'm; all timestamps are UTC.
 */
@Entity
@Table(name = "bookings")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Booking {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stadium_id", nullable = false)
    private Stadium stadium;

    /** Null for owner manual/walk-in bookings, which carry clientName/clientPhone instead. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    /** Set when source = 'game'. Kept as a raw id; the reverse link is games.booking_id. */
    @Column(name = "game_id")
    private UUID gameId;

    /** Set when this booking was generated from a recurring series (step 20). */
    @Column(name = "booking_series_id")
    private UUID bookingSeriesId;

    /** One of: app, manual, game. */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String source = "app";

    /**
     * One of: pending, confirmed, completed, expired,
     * cancelled_by_user, cancelled_by_owner.
     */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "pending";

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "duration_hours", nullable = false)
    private Integer durationHours;

    /** Integer so'm — never floating point for money. */
    @Column(nullable = false)
    private Long price;

    @Column(name = "client_name", length = 100)
    private String clientName;

    @Column(name = "client_phone", length = 20)
    private String clientPhone;

    @Builder.Default
    @Column(name = "is_walkin", nullable = false)
    private boolean isWalkin = false;

    /** For pending app bookings: the hold deadline (now + holdExpiryMinutes). */
    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column(name = "cancellation_reason", columnDefinition = "text")
    private String cancellationReason;

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
