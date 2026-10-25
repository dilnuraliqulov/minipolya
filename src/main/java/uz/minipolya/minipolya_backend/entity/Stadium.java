package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * A football pitch. 1 stadium = 1 pitch. Visible to players only when
 * {@code status = 'approved'}. Carries its own configurable cancellation policy.
 */
@Entity
@Table(name = "stadiums")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Stadium {

    /** 50 000 tiyin = 500 so'm, the smallest payment ATMOS accepts. */
    public static final long MIN_BOOKING_AMOUNT_FLOOR = 50_000L;

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(nullable = false, length = 300)
    private String address;

    @Column(precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(precision = 9, scale = 6)
    private BigDecimal longitude;

    @Column(length = 20)
    private String phone;

    @Column(columnDefinition = "text")
    private String description;

    /** One of: 5x5, 7x7, 9x9, 11x11. */
    @Column(nullable = false, length = 10)
    private String format;

    /** One of: natural, artificial, hybrid. */
    @Column(name = "surface_type", nullable = false, length = 20)
    private String surfaceType;

    @Column(name = "open_time", nullable = false)
    private LocalTime openTime;

    @Column(name = "close_time", nullable = false)
    private LocalTime closeTime;

    /** Integer so'm — never floating point for money. */
    @Column(name = "price_per_hour", nullable = false)
    private Long pricePerHour;

    /**
     * Smallest amount, in tiyin, a player may pay up front to hold a booking
     * here. {@code null} means the whole price is due immediately. The floor is
     * {@link #MIN_BOOKING_AMOUNT_FLOOR} — below that ATMOS will not take the
     * payment at all.
     */
    @Column(name = "min_booking_amount")
    private Long minBookingAmount;

    /** One of: pending, approved, blocked. */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "pending";

    @Builder.Default
    @Column(name = "full_refund_hours", nullable = false)
    private int fullRefundHours = 24;

    @Builder.Default
    @Column(name = "partial_refund_hours", nullable = false)
    private int partialRefundHours = 12;

    @Builder.Default
    @Column(name = "partial_refund_percent", nullable = false)
    private int partialRefundPercent = 50;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Builder.Default
    @OneToMany(mappedBy = "stadium", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<StadiumPhoto> photos = new ArrayList<>();

    @OneToOne(mappedBy = "stadium", cascade = CascadeType.ALL, orphanRemoval = true)
    private StadiumAmenities amenities;

    public void addPhoto(StadiumPhoto photo) {
        photo.setStadium(this);
        this.photos.add(photo);
    }

    public void setAmenities(StadiumAmenities amenities) {
        if (amenities != null) {
            amenities.setStadium(this);
        }
        this.amenities = amenities;
    }

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
