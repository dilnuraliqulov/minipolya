package uz.minipolya.minipolya_backend.dto.request;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Amenity flags supplied when creating or updating a stadium. Any omitted
 * flag is false (on update the whole block replaces the old one when present). The
 * all-args constructor is private so Jackson binds through the setters: otherwise it
 * would pick that constructor and refuse a block that leaves a flag out.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AmenitiesRequest {

    private boolean parking;
    private boolean lighting;
    private boolean showers;
    private boolean lockerRoom;
    private boolean wifi;
    private boolean cafe;
    private boolean equipmentRental;
    private boolean tribune;
    private boolean toilet;
    private boolean covered;
    private boolean bibs;
    private boolean drinkingWater;
}
