package uz.minipolya.minipolya_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmenitiesResponse {

    private boolean parking;
    private boolean lighting;
    private boolean showers;
    private boolean lockerRoom;
    private boolean wifi;
    private boolean cafe;
    private boolean equipmentRental;
    private boolean tribune;
}
