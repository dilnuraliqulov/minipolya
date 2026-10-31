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
 * A money movement: a charge (positive amount) or a refund (negative amount).
 * Refunds are always NEW rows pointing at the original via
 * {@code refundOfPayment}; an existing row's amount is never mutated.
 * {@code providerTxnId} is UNIQUE, which makes duplicate webhook calls
 * idempotent. All money is integer so'm.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Payment {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Null for game_share payments that have no direct booking yet. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;

    /** Null for plain booking payments. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_id")
    private Game game;

    /**
     * For a team-vs-team game: which team of which game this money belongs to.
     * A game's booking is replaced whenever an opponent leaves and another
     * joins, so the booking cannot own a team's payments — this can.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "game_team_id")
    private GameTeam gameTeam;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * Tiyin: the gross a card was charged (card fee included) or the cash taken —
     * positive for charges, negative for refunds.
     */
    @Column(nullable = false)
    private Long amount;

    /**
     * Tiyin the card fee and the price rounding added on top of the net this payment
     * covers, so {@code amount - cardFeeExpected} is that net. 0 for cash.
     */
    @Builder.Default
    @Column(name = "card_fee_expected", nullable = false)
    private Long cardFeeExpected = 0L;

    /** The commission the provider reports having taken; null until it does. */
    @Column(name = "provider_commission")
    private Long providerCommission;

    /**
     * The part of {@link #amount} owed to the stadium owner. Together with
     * {@link #platformAmount} it always adds up to {@code amount}, and neither
     * ever changes once the row exists.
     */
    @Column(name = "owner_amount", nullable = false)
    private Long ownerAmount;

    /** The part of {@link #amount} the platform keeps. */
    @Column(name = "platform_amount", nullable = false)
    private Long platformAmount;

    /** What this payment is worth to its recipients: the gross less the card fee. */
    public long netAmount() {
        return (amount == null ? 0L : amount) - (cardFeeExpected == null ? 0L : cardFeeExpected);
    }

    /** The booking commission in force when this payment was taken. */
    @Column(name = "commission_percent", nullable = false)
    private Integer commissionPercent;

    /** How the money moved: by card through the provider, or cash to the owner. */
    @Convert(converter = MethodConverter.class)
    @Column(nullable = false, length = 20)
    private Method method;

    @Convert(converter = PaymentTypeConverter.class)
    @Column(name = "payment_type", nullable = false, length = 20)
    private PaymentType paymentType;

    @Convert(converter = ProviderConverter.class)
    @Column(nullable = false, length = 20)
    private Provider provider;

    /**
     * The provider's handle: the charge's transaction ids (comma-separated, one per split)
     * or the hold id. Null for cash. UNIQUE at the DB level.
     */
    @Column(name = "provider_txn_id", length = 255, unique = true)
    private String providerTxnId;

    /** The ids a confirmed charge returned, comma-separated: what a reverse needs. */
    @Column(name = "provider_success_txn_ids", length = 500)
    private String providerSuccessTxnIds;

    /** The fiscal receipt for this payment, when the provider issued one. */
    @Column(name = "ofd_url", length = 500)
    private String ofdUrl;

    /** When an unsettled hold lapses at the provider. */
    @Column(name = "hold_expires_at")
    private OffsetDateTime holdExpiresAt;

    @Builder.Default
    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "refund_reason", length = 255)
    private String refundReason;

    /** On a refund the provider declined: the provider's error, for the admin. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    /** A failed refund an admin marked as returned outside the platform (cash, bank transfer). */
    @Builder.Default
    @Column(name = "refunded_manually", nullable = false)
    private boolean refundedManually = false;

    @Column(name = "manual_refund_reason", length = 500)
    private String manualRefundReason;

    /** The admin who marked it refunded by hand. */
    @Column(name = "manual_refund_by")
    private UUID manualRefundBy;

    /** For refund rows: the original payment being refunded. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "refund_of_payment_id")
    private Payment refundOfPayment;

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

    // ---- Enums (stored lowercase to match the DB CHECK constraints) ----

    public enum PaymentType {
        /** First payment on a booking: the stadium minimum or the full price. */
        BOOKING,
        /** What was left of a booking price after its first payment. */
        REMAINDER,
        /** One team's share of a team-vs-team game. */
        TEAM_GAME,
        /** Legacy casual/open game share, via the hosted checkout. */
        GAME_SHARE,
        SERVICE_FEE,
        REFUND;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static PaymentType fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum Method {
        CARD, CASH;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Method fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum Provider {
        /** The card provider for every current flow. */
        ATMOS,
        /** Legacy hosted checkout, kept until the team-vs-team work drops it. */
        PAYME,
        CLICK,
        CASH;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Provider fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    /**
     * A card charge lands on {@link #PAID}; a hold lands on {@link #HELD} and
     * then moves forward exactly once, to {@link #CAPTURED} or
     * {@link #RELEASED}. A status only ever moves forward.
     *
     * <p>{@link #PENDING} and {@link #COMPLETED} belong to the legacy hosted
     * checkout and disappear with it.
     */
    public enum Status {
        /** Written, provider called, answer not in yet (or lost): settled by the reconcile job. */
        PROCESSING,
        /** Being given back — a charge reversed or a hold released; the reconcile job finishes it. */
        RETURN_PENDING,
        /** A hold being taken; the reconcile job finishes it. */
        CAPTURE_PENDING,
        /**
         * The provider declined to give this payment back: the money is still owed. Its
         * {@code failed} refund row waits for an admin to retry it or mark it refunded by hand;
         * the reconcile job leaves it alone.
         */
        REFUND_FAILED,
        HELD, CAPTURED, RELEASED, PAID, REFUNDED, FAILED, PENDING, COMPLETED;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Status fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    // ---- JPA converters: enum <-> lowercase DB string ----

    @Converter
    public static class PaymentTypeConverter implements AttributeConverter<PaymentType, String> {
        @Override
        public String convertToDatabaseColumn(PaymentType attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public PaymentType convertToEntityAttribute(String dbData) {
            return dbData == null ? null : PaymentType.fromValue(dbData);
        }
    }

    @Converter
    public static class MethodConverter implements AttributeConverter<Method, String> {
        @Override
        public String convertToDatabaseColumn(Method attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public Method convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Method.fromValue(dbData);
        }
    }

    @Converter
    public static class ProviderConverter implements AttributeConverter<Provider, String> {
        @Override
        public String convertToDatabaseColumn(Provider attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public Provider convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Provider.fromValue(dbData);
        }
    }

    @Converter
    public static class StatusConverter implements AttributeConverter<Status, String> {
        @Override
        public String convertToDatabaseColumn(Status attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public Status convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Status.fromValue(dbData);
        }
    }
}
