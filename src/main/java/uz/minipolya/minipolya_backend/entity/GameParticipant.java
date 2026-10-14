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
 * A user's participation in a game. Unique per (game, user). {@code side} is
 * null until assigned (on confirm for casual/open; up front for team_vs_team).
 * {@code payment} links a paid share — null for non-paying team members.
 */
@Entity
@Table(name = "game_participants")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameParticipant {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Convert(converter = SideConverter.class)
    @Column(length = 1)
    private Side side;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Builder.Default
    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 20)
    private Status status = Status.JOINED;

    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) {
            joinedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    // ---- Enums (stored lowercase to match the DB CHECK constraints) ----

    public enum Side {
        A, B;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Side fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum Status {
        JOINED, PAID, CANCELLED, REFUNDED;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Status fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Converter
    public static class SideConverter implements AttributeConverter<Side, String> {
        @Override
        public String convertToDatabaseColumn(Side attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public Side convertToEntityAttribute(String dbData) {
            return dbData == null ? null : Side.fromValue(dbData);
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
