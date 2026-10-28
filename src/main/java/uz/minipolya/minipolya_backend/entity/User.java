package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import org.hibernate.annotations.UuidGenerator;
import java.util.UUID;
import jakarta.persistence.Id;
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
 * Platform user. Everyone can book (player is the default, no flag needed).
 * {@code isOwner} / {@code isAdmin} promote the same record to owner / admin —
 * a single user can be both an owner and a player at once.
 */
@Entity
@Table(name = "users")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    /** Nullable: Telegram initData carries no phone, so a Telegram-created user starts without one. */
    @Column(length = 20, unique = true)
    private String phone;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "telegram_id", unique = true)
    private Long telegramId;

    @Builder.Default
    @Column(nullable = false, length = 8)
    private String language = "uz";

    @Builder.Default
    @Column(name = "is_owner", nullable = false)
    private boolean isOwner = false;

    @Builder.Default
    @Column(name = "is_admin", nullable = false)
    private boolean isAdmin = false;

    /** Blocked by an admin: cannot sign in, and an existing JWT stops working at once. */
    @Builder.Default
    @Column(name = "is_blocked", nullable = false)
    private boolean isBlocked = false;

    /**
     * Admin password sign-in only (see AdminAuthService); null for everyone who signs in
     * through Telegram. Unique case-insensitively in the database.
     */
    @Column(length = 64)
    private String username;

    /** BCrypt hash, never the password itself. Admin password sign-in only. */
    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    /** Wrong passwords in a row since the last success or lockout. */
    @Builder.Default
    @Column(name = "failed_login_attempts", nullable = false)
    private int failedLoginAttempts = 0;

    /** Password sign-in is refused until this moment; null when not locked. */
    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    /**
     * When the password last changed. Tokens issued before this are refused by
     * JwtAuthFilter, so a password reset in the database signs out every older session.
     */
    @Column(name = "password_changed_at")
    private OffsetDateTime passwordChangedAt;

    /** Latest successful password sign-in. */
    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

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
