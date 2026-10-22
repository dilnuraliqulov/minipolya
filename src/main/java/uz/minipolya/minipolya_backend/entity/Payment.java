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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /** Integer so'm — positive for charges, negative for refunds. */
    @Column(nullable = false)
    private Integer amount;

    @Convert(converter = PaymentTypeConverter.class)
    @Column(name = "payment_type", nullable = false, length = 20)
    private PaymentType paymentType;

    @Convert(converter = ProviderConverter.class)
    @Column(nullable = false, length = 20)
    private Provider provider;

    /** Null for cash. UNIQUE at the DB level — the webhook idempotency key. */
    @Column(name = "provider_txn_id", length = 255, unique = true)
    private String providerTxnId;

    @Builder.Default
    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "refund_reason", length = 255)
    private String refundReason;

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
        BOOKING, GAME_SHARE, SERVICE_FEE, REFUND;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static PaymentType fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum Provider {
        PAYME, CLICK, CASH;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Provider fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum Status {
        PENDING, COMPLETED, FAILED, REFUNDED;

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
