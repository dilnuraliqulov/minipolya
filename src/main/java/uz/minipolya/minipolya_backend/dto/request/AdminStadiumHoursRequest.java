package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The weekly opening schedule an admin sets for a stadium
 * ({@code PUT /api/admin/stadiums/{id}/hours}). The whole week is sent each time and
 * checked by the same {@code WeeklyHoursRules} the owner's create and update use.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdminStadiumHoursRequest {

    @Valid
    @NotNull(message = "hours is required")
    private WeeklyHoursRequest hours;
}
