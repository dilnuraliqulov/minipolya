package uz.minipolya.minipolya_backend.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Amenity flags supplied when creating or updating a stadium. Any omitted
 * flag defaults to false on create; on update, only the whole block is applied
 * when present.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmenitiesRequest {

    private boolean parking;
    private boolean lighting;
    private boolean showers;
    private boolean lockerRoom;
    private boolean wifi;
    private boolean cafe;
    private boolean equipmentRental;
    private boolean tribune;
}
