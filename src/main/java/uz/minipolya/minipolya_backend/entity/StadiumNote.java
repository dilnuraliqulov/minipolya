package uz.minipolya.minipolya_backend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
import java.util.UUID;

/** An admin's internal note on a stadium. Admins only; never edited or deleted. */
@Entity
@Table(name = "stadium_notes")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StadiumNote {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "stadium_id", nullable = false)
    private UUID stadiumId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    /** The admin's name when they wrote it. */
    @Column(name = "author_name", nullable = false, length = 120)
    private String authorName;

    @Column(nullable = false, length = 1000)
    private String text;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = OffsetDateTime.now(ZoneOffset.UTC);
        }
    }
}
