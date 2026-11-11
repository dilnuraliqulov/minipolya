package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** A value the bot keeps between restarts, by key (e.g. the banner's Telegram file_id). Never a secret. */
@Entity
@Table(name = "telegram_bot_values")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TelegramBotValue {

    @Id
    @Column(length = 64)
    private String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public TelegramBotValue(String key, String value) {
        this.key = key;
        this.value = value;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
