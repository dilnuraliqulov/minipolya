package uz.minipolya.minipolya_backend.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
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
import java.util.Locale;

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

    /**
     * Every booking is exactly one hour: one availability slot. App, manual, recurring and
     * game bookings alike. Rows from before this rule may be longer and keep their length.
     */
    public static final int HOURS = 1;

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

    /** Always {@link #HOURS} for a new booking; an older row may be longer. */
    @Column(name = "duration_hours", nullable = false)
    private Integer durationHours;

    /** The owner's own price in tiyin — what the owner set, never converted. */
    @Column(nullable = false)
    private Long price;

    /**
     * The player price D for the whole booking, in tiyin: the sum of each hour's player
     * price when it was booked. Null for manual bookings (the walk-in pays the owner's own
     * price) and for older bookings.
     */
    @Column(name = "card_price")
    private Long cardPrice;

    /** How {@link #paidAmount} and the remainder are counted; see {@link PricingModel}. */
    @Builder.Default
    @Convert(converter = PricingModelConverter.class)
    @Column(name = "pricing_model", nullable = false, length = 20)
    private PricingModel pricingModel = PricingModel.PLAYER_PRICE;

    /**
     * Running total of this booking's successful payments, in tiyin: what the player paid
     * (card and cash) under {@code player_price}, the owner's net under {@code net}.
     */
    @Builder.Default
    @Column(name = "paid_amount", nullable = false)
    private Long paidAmount = 0L;

    @Builder.Default
    @Convert(converter = PaymentStatusConverter.class)
    @Column(name = "payment_status", nullable = false, length = 20)
    private PaymentStatus paymentStatus = PaymentStatus.UNPAID;

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

    /**
     * What is still owed on this booking, in tiyin — never negative, so an
     * overpayment reads as nothing left to pay. Derived, not stored: the
     * mapper picks it up for the API response.
     */
    public long getRemainingAmount() {
        long paid = paidAmount == null ? 0L : paidAmount;
        return Math.max(0L, totalDue() - paid);
    }

    /**
     * What the booking costs in the terms {@link #paidAmount} is counted in: the player
     * price D (the owner's own price on a manual booking) under {@code player_price}, the
     * owner's price under {@code net}.
     */
    public long totalDue() {
        if (pricingModel == PricingModel.PLAYER_PRICE && cardPrice != null) {
            return cardPrice;
        }
        return price == null ? 0L : price;
    }

    /** What a successful payment counts towards {@link #totalDue()}. */
    public long creditOf(Payment payment) {
        return pricingModel == PricingModel.PLAYER_PRICE
                ? (payment.getAmount() == null ? 0L : payment.getAmount())
                : payment.netAmount();
    }

    /**
     * How a booking counts its money. {@code PLAYER_PRICE}: every booking made under the
     * one-player-price rule — the remainder is {@code D - paid}, the same in the app and in
     * cash. {@code NET}: bookings made before it, and game bookings (whose teams' debts live
     * on {@code game_teams}) — the owner's terms, as they always were.
     */
    public enum PricingModel {
        NET, PLAYER_PRICE;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Converter
    public static class PricingModelConverter implements AttributeConverter<PricingModel, String> {
        @Override
        public String convertToDatabaseColumn(PricingModel attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public PricingModel convertToEntityAttribute(String dbData) {
            return dbData == null ? null : PricingModel.valueOf(dbData.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** How much of the price has been paid. */
    public enum PaymentStatus {
        UNPAID, PARTIALLY_PAID, PAID;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static PaymentStatus fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Converter
    public static class PaymentStatusConverter implements AttributeConverter<PaymentStatus, String> {
        @Override
        public String convertToDatabaseColumn(PaymentStatus attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public PaymentStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : PaymentStatus.fromValue(dbData);
        }
    }
}
