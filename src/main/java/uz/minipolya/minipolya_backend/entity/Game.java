package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
 * A match three ways: {@code casual} (private, play with friends),
 * {@code open} (public, find players) and {@code team_vs_team}. Stays
 * {@code open} until fully paid, when the slot is taken by a {@link Booking}
 * (wired via {@code bookingId} in step 8). Full flow logic lands in step 10.
 */
@Entity
@Table(name = "games")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Game {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creator_id", nullable = false)
    private User creator;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stadium_id", nullable = false)
    private Stadium stadium;

    /**
     * The booking created once the game fills. Kept as a raw id until step 8
     * adds the bookings table and its FK; promoted to a relationship there.
     */
    @Column(name = "booking_id")
    private Long bookingId;

    /** One of: casual, open, team_vs_team. */
    @Column(nullable = false, length = 20)
    private String type;

    /**
     * One of: open, confirmed, completed, cancelled_slot_taken,
     * cancelled_by_creator, cancelled_not_filled.
     */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "open";

    @Column(length = 200)
    private String title;

    @Column(columnDefinition = "text")
    private String description;

    @Column(name = "max_players", nullable = false)
    private Integer maxPlayers;

    @Column(name = "min_players")
    private Integer minPlayers;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    /** Integer so'm — never floating point for money. */
    @Column(name = "total_price", nullable = false)
    private Integer totalPrice;

    @Builder.Default
    @Column(name = "service_fee_per_person", nullable = false)
    private Integer serviceFeePerPerson = 5000;

    /** Share of the pitch price the creator's team pays in team_vs_team (1..100). */
    @Builder.Default
    @Column(name = "creator_share_pct", nullable = false)
    private Integer creatorSharePct = 50;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_a_id")
    private Team teamA;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_b_id")
    private Team teamB;

    @Builder.Default
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

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
