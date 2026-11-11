package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * A Telegram update already handled. Written by an insert that ignores a duplicate
 * ({@code TelegramProcessedUpdateRepository.claim}), which is what makes handling idempotent.
 */
@Entity
@Table(name = "telegram_processed_updates")
@Getter
@Setter
@NoArgsConstructor
public class TelegramProcessedUpdate {

    /** Telegram's own update_id, not a UUID. */
    @Id
    @Column(name = "update_id")
    private Long updateId;

    @Column(name = "received_at", nullable = false)
    private OffsetDateTime receivedAt;
}
