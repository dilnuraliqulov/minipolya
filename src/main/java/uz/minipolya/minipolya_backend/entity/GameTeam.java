package uz.minipolya.minipolya_backend.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
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
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;

/**
 * One team's occupancy of one side of a team-vs-team game, and the unit its
 * money hangs off: the platform fee and owner advance it paid to join, and the
 * remainder it still owes. Every {@link Payment} a team makes for a game points
 * here.
 *
 * <p>This is deliberately not the same thing as {@code games.team_a_id}. A
 * confirmed game can fall back to WAITING when one team leaves, and a new
 * opponent then takes that side: the leaver's row stays as {@code CANCELLED}
 * with its payments attached, and the newcomer gets a fresh {@code ACTIVE} row.
 * A partial unique index keeps at most one active row per (game, side).
 *
 * <p>All amounts are tiyin, and none of them change once the row exists — the
 * only field that moves is {@link #remainderStatus}.
 */
@Entity
@Table(name = "game_teams")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GameTeam {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_id", nullable = false)
    private Team team;

    /** The team leader who paid, and the only user who may settle or cancel for this team. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "leader_id", nullable = false)
    private User leader;

    /** Shares {@link GameParticipant.Side} so a game has one vocabulary for sides. */
    @Convert(converter = GameParticipant.SideConverter.class)
    @Column(nullable = false, length = 1)
    private GameParticipant.Side side;

    @Builder.Default
    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 20)
    private Status status = Status.ACTIVE;

    /** The platform fee this team paid, tiyin. Never part of the pitch price. */
    @Column(name = "fee_amount", nullable = false)
    private Long feeAmount;

    /** The owner advance this team paid up front, tiyin. This part reaches the booking. */
    @Column(name = "advance_amount", nullable = false)
    private Long advanceAmount;

    /** What this team still owes on the pitch, tiyin. Zero when the advance covered it. */
    @Column(name = "remainder_amount", nullable = false)
    private Long remainderAmount;

    @Builder.Default
    @Convert(converter = RemainderStatusConverter.class)
    @Column(name = "remainder_status", nullable = false, length = 20)
    private RemainderStatus remainderStatus = RemainderStatus.UNPAID;

    /** When this team took the side — also where its view of the game chat starts. */
    @Column(name = "joined_at", nullable = false, updatable = false)
    private OffsetDateTime joinedAt;

    @Column(name = "left_at")
    private OffsetDateTime leftAt;

    /**
     * T5: what this team chose to pay on joining — {@code MINIMUM} or {@code FULL}. Null on rows
     * from before (changeset 052), which keep their stored amounts and behaviour.
     */
    @Convert(converter = PaymentOptionConverter.class)
    @Column(name = "payment_option", length = 10)
    private PaymentOption paymentOption;

    /** What this team's card was held or charged on joining, as players pay it. Null on older rows. */
    @Column(name = "join_price")
    private Long joinPrice;

    /** What this team still owes after joining, as players pay it (app or cash). Null on older rows. */
    @Column(name = "remainder_price")
    private Long remainderPrice;

    /** T10: the owner's mark on this team's remainder, as on a booking. Null = no mark. */
    @Convert(converter = Booking.RemainderMarkConverter.class)
    @Column(name = "remainder_mark", length = 20)
    private Booking.RemainderMark remainderMark;

    /** When the mark was set (UTC): the owner may undo it until the end of that Tashkent day. */
    @Column(name = "remainder_marked_at")
    private OffsetDateTime remainderMarkedAt;

    @Column(name = "remainder_marked_by")
    private UUID remainderMarkedBy;

    @PrePersist
    void onCreate() {
        if (joinedAt == null) {
            joinedAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }

    /** Sets the owner's mark on the remainder. */
    public void markRemainder(Booking.RemainderMark mark, UUID userId, OffsetDateTime at) {
        this.remainderMark = mark;
        this.remainderMarkedAt = at;
        this.remainderMarkedBy = userId;
    }

    /** Clears the owner's mark. */
    public void clearRemainderMark() {
        this.remainderMark = null;
        this.remainderMarkedAt = null;
        this.remainderMarkedBy = null;
    }

    /** True when this team still owes money on the pitch. */
    public boolean owesRemainder() {
        if (remainderStatus != RemainderStatus.UNPAID) {
            return false;
        }
        if (remainderPrice != null) {
            return remainderPrice > 0;
        }
        return remainderAmount != null && remainderAmount > 0;
    }

    /** What this team has put towards the pitch price so far, tiyin (the fee is not part of it). */
    public long paidTowardsPitch() {
        long advance = advanceAmount == null ? 0L : advanceAmount;
        return remainderStatus == RemainderStatus.PAID
                ? advance + (remainderAmount == null ? 0L : remainderAmount)
                : advance;
    }

    // ---- Enums (stored lowercase to match the DB CHECK constraints) ----

    /**
     * {@code ACTIVE} is the team currently on this side; {@code CANCELLED} is one that left;
     * {@code JOINING} is a team whose join has started (slot booked, its card charged) but
     * not finished (the waiting team's hold not yet captured) — it holds its side meanwhile.
     */
    public enum Status {
        ACTIVE, JOINING, CANCELLED;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Status fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    public enum RemainderStatus {
        UNPAID, PAID;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static RemainderStatus fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    /** T6: what a team pays on joining — the stadium minimum, or its whole half of the pitch. */
    public enum PaymentOption {
        MINIMUM, FULL;

        @JsonValue
        public String getValue() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static PaymentOption fromValue(String value) {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    @Converter
    public static class PaymentOptionConverter implements AttributeConverter<PaymentOption, String> {
        @Override
        public String convertToDatabaseColumn(PaymentOption attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public PaymentOption convertToEntityAttribute(String dbData) {
            return dbData == null ? null : PaymentOption.fromValue(dbData);
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

    @Converter
    public static class RemainderStatusConverter implements AttributeConverter<RemainderStatus, String> {
        @Override
        public String convertToDatabaseColumn(RemainderStatus attribute) {
            return attribute == null ? null : attribute.getValue();
        }

        @Override
        public RemainderStatus convertToEntityAttribute(String dbData) {
            return dbData == null ? null : RemainderStatus.fromValue(dbData);
        }
    }
}
