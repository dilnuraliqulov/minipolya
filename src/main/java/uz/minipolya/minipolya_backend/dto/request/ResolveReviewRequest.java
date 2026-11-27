package uz.minipolya.minipolya_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * F6: how an admin settles a payment left {@code needs_review}, after checking it at ATMOS.
 * {@code outcome} is {@code captured} (the money was taken and is kept), {@code released}
 * (nothing was taken) or {@code refunded} (it was taken and has since been given back, by
 * ATMOS or by hand). {@code reason} (10–500 characters) says how the admin knows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolveReviewRequest {

    private String outcome;
    private String reason;
}
