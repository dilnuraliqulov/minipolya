package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** A team a user is in, for the admin panel's user page. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AdminUserTeamResponse {

    private UUID id;
    private String name;
    private String leaderName;
    private long memberCount;
    /** This user's part in it: {@code leader} or {@code member}. */
    private String role;
}
