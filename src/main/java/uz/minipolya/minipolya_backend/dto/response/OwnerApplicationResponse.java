package uz.minipolya.minipolya_backend.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import uz.minipolya.minipolya_backend.entity.OwnerApplication;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OwnerApplicationResponse {

    private Long id;
    private Long userId;
    private String userName;
    private String userPhone;
    private String businessName;
    private String businessAddress;
    private String phone;
    private String description;
    private Integer stadiumCount;
    private OwnerApplication.Status status;
    private Long reviewedBy;
    private String reviewerName;
    private String reviewNote;
    private OffsetDateTime reviewedAt;
    private OffsetDateTime createdAt;
}
