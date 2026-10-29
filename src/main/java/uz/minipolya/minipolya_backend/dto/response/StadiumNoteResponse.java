package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/** An admin's internal note on a stadium. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StadiumNoteResponse {

    private UUID id;
    private String text;
    private UUID authorId;
    /** The admin's name when they wrote it. */
    private String authorName;
    private OffsetDateTime createdAt;
}
