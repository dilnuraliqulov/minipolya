package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.TeamLevel;

import java.util.UUID;

/**
 * Lean team summary used in listings. Omits the member list — clients fetch
 * members via the detail endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TeamResponse {

    private UUID id;
    private String name;
    private String leaderName;
    private Integer memberCount;

    /** {@code havaskor} / {@code orta} / {@code professional}; absent when the team has none yet. */
    private TeamLevel level;
}
