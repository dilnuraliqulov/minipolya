package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SendGameMessageRequest {

    /**
     * The longest message the chat takes. It is sent to the clients as
     * {@code chatMessageMaxLength} on a team game's detail, so a composer's limit and its
     * counter come from here rather than from a number copied into each one.
     */
    public static final int MESSAGE_MAX = 1000;

    @NotBlank
    @Size(max = MESSAGE_MAX, message = "message must be at most 1000 characters")
    private String message;
}
