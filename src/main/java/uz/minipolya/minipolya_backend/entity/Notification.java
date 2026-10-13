package uz.minipolya.minipolya_backend.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
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
 * An in-app notification, optionally pushed to the user via Telegram.
 * {@code userId} is a raw id (not a relationship) so the mark-all-read bulk
 * update can address it directly. {@code referenceType}/{@code referenceId}
 * let the frontend deep-link to the related entity.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Notification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Convert(converter = TypeConverter.class)
    @Column(nullable = false, length = 50)
    private Type type;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Convert(converter = ReferenceTypeConverter.class)
    @Column(name = "reference_type", length = 50)
    private ReferenceType referenceType;

    @Column(name = "reference_id")
    private Long referenceId;

    @Builder.Default
    @Column(name = "is_read", nullable = false)
    private boolean isRead = false;

    @Builder.Default
    @Column(name = "sent_via_telegram", nullable = false)
    private boolean sentViaTelegram = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    // ---- Enums (stored lowercase to match the DB CHECK) ----

    public enum Type {
        BOOKING_CONFIRMED, BOOKING_CANCELLED, BOOKING_REMINDER, BOOKING_EXPIRED,
        GAME_CONFIRMED, GAME_CANCELLED, GAME_PLAYER_JOINED, GAME_PLAYER_LEFT,
        GAME_PAYMENT_RECEIVED, REVIEW_REPLY, STADIUM_APPROVED, STADIUM_REJECTED,
        OWNER_APPLICATION_STATUS, PROMOTION_NEW;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Type fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum ReferenceType {
        BOOKING, GAME, STADIUM, REVIEW, APPLICATION;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static ReferenceType fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Converter
    public static class TypeConverter implements AttributeConverter<Type, String> {
        @Override
        public String convertToDatabaseColumn(Type attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public Type convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Type.fromValue(dbData);
        }
    }

    @Converter
    public static class ReferenceTypeConverter implements AttributeConverter<ReferenceType, String> {
        @Override
        public String convertToDatabaseColumn(ReferenceType attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public ReferenceType convertToEntityAttribute(String dbData) {
            return dbData == null ? null : ReferenceType.fromValue(dbData);
        }
    }
}
