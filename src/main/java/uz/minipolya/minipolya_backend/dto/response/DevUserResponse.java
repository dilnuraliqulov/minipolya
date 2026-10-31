package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** One user in the dev-only sign-in picker ({@code dev} profile only). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DevUserResponse {

    private UUID id;
    private String name;

    @JsonProperty("isOwner")
    private boolean owner;

    @JsonProperty("isAdmin")
    private boolean admin;

    @JsonProperty("isBlocked")
    private boolean blocked;
}
