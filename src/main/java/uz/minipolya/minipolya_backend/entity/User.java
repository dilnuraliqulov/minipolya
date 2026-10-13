package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
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
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
