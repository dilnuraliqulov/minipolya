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
 * One chat message in a team_vs_team game. Chat is REST/polling-based (no
 * WebSocket) for the MVP.
 */
@Entity
@Table(name = "game_messages")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameMessage {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    /** Who sent it — null on a system line, which nobody sent (changeset 055). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    /** {@code user} or {@code system} (changeset 055); every row written before it is a user's. */
    @Builder.Default
    @Convert(converter = KindConverter.class)
    @Column(nullable = false, length = 10)
    private Kind kind = Kind.USER;

    /**
     * Which event a system line marks — {@link #EVENT_GAME_CONFIRMED} or
     * {@link #EVENT_TEAM_JOINED}. Null on a player's message.
     */
    @Column(length = 40)
    private String event;

    /** The team a system line is about, when it is about one ({@code team_joined}). */
    @Column(name = "event_team_name", length = 100)
    private String eventTeamName;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** The opponent joined and the hour is booked: the game is on. */
    public static final String EVENT_GAME_CONFIRMED = "game_confirmed";

    /** A team took a side — the one named in {@code eventTeamName}. */
    public static final String EVENT_TEAM_JOINED = "team_joined";

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    /** A player's message, or a line the platform wrote. */
    public enum Kind {
        USER, SYSTEM;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Kind fromValue(String value) {
            return value == null ? null : valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Converter
    public static class KindConverter implements AttributeConverter<Kind, String> {
        @Override
        public String convertToDatabaseColumn(Kind attribute) {
            return attribute == null ? Kind.USER.getValue() : attribute.getValue();
        }

        @Override
        public Kind convertToEntityAttribute(String dbData) {
            return dbData == null ? Kind.USER : Kind.fromValue(dbData);
        }
    }
}
