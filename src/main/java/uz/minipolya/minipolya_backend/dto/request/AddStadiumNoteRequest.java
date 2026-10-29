package uz.minipolya.minipolya_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A new internal note. {@code text} (1–1000 characters) is checked by the service, so the
 * errors read exactly {@code "text is required"} and {@code "text must be at most 1000 characters"}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddStadiumNoteRequest {

    private String text;
}
