package uz.minipolya.minipolya_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Marks a failed refund as returned outside the platform. {@code reason} (10–500
 * characters) is checked by the service, so the errors read exactly
 * {@code "reason must be at least 10 characters"} and so on.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarkRefundedRequest {

    private String reason;
}
