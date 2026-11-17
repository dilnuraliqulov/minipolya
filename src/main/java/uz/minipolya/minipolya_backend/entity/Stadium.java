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
import uz.minipolya.minipolya_backend.util.WeeklyHours;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** One of the twelve Tashkent districts ({@code Districts}); null for stadiums added before it existed. */
    @Column(length = 30)
    private String district;

    /** "Moʻljal": a landmark near the pitch, optional (trimmed, blank stored as null). */
    @Column(length = 150)
    private String landmark;

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

    /**
     * One of: pending, approved, rejected (turned down at review), blocked (taken off after
     * approval). Players only ever see approved stadiums.
     */
    @Builder.Default
    @Column(nullable = false, length = 20)
    private String status = "pending";

    /** The admin's reason for a rejected or blocked status, shown to the owner; null otherwise. */
    @Column(name = "status_reason", columnDefinition = "text")
    private String statusReason;

    /**
     * How many hours before kick-off a player may still cancel and get their
     * money back. Cancelling at or beyond this refunds every card payment in
     * full; later cancels the booking but refunds nothing.
     */
    @Builder.Default
    @Column(name = "cancellation_hours", nullable = false)
    private int cancellationHours = 24;

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

    /**
     * The weekly opening schedule (changeset 053), one row per day of the week. Read it
     * through {@link #weeklyHours()} and write it through
     * {@link #setWeeklyHours(WeeklyHours)}, never by mutating this list.
     */
    @Builder.Default
    @OneToMany(mappedBy = "stadium", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("dayOfWeek ASC")
    private List<StadiumHours> hours = new ArrayList<>();

    /**
     * The stadium's weekly schedule. Falls back to the legacy {@code openTime} /
     * {@code closeTime} as "the same every day" when the seven rows are not there (a row
     * inserted by hand, a stadium built in a test), so a stadium never becomes unbookable
     * for want of them.
     */
    public WeeklyHours weeklyHours() {
        return StadiumHours.toWeekly(hours, openTime, closeTime);
    }

    /**
     * Replaces the whole schedule — the seven rows in place, so orphan removal deletes the
     * old ones — and keeps the legacy {@code openTime} / {@code closeTime} columns in step
     * with the first open day, so older readers and clients still see sensible hours.
     */
    public void setWeeklyHours(WeeklyHours weekly) {
        if (hours == null) {
            hours = new ArrayList<>();
        }
        // The seven rows are updated in place, keyed by day of week, never cleared and
        // re-added: Hibernate orders the inserts before the deletes, so replacing them
        // would collide with uq_stadium_hours_day.
        Map<Integer, StadiumHours> existing = new HashMap<>();
        for (StadiumHours row : hours) {
            existing.put(row.getDayOfWeek(), row);
        }
        List<StadiumHours> kept = new ArrayList<>(WeeklyHours.DAYS);
        for (WeeklyHours.Day day : weekly.days()) {
            StadiumHours row = existing.remove(day.dayOfWeek());
            if (row == null) {
                row = StadiumHours.builder().stadium(this).dayOfWeek(day.dayOfWeek()).build();
            }
            row.setClosed(day.closed());
            row.setOpenTime(day.open());
            row.setCloseTime(day.close());
            kept.add(row);
        }
        // Anything left over is a duplicate or an out-of-range day: orphan removal deletes it.
        hours.clear();
        hours.addAll(kept);

        weekly.days().stream()
                .filter(day -> !day.closed())
                .findFirst()
                .ifPresent(day -> {
                    openTime = day.open();
                    closeTime = day.close();
                });
    }

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
