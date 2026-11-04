package uz.minipolya.minipolya_backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

/** Every photo of the stadium, each exactly once, in the new order; the first is the cover. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReorderPhotosRequest {

    @NotNull
    private List<@NotNull UUID> photoIds;
}
