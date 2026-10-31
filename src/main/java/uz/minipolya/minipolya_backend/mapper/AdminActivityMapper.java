package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.AdminBookingResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminGamePlayerResponse;
import uz.minipolya.minipolya_backend.dto.response.AdminGameResponse;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;

/**
 * Bookings and games as the admin panel lists them. Only the fields read straight off
 * the entity are mapped here: the booking's own response (card prices, cancellation
 * figures), the money totals, the teams and the cancellation details need other rows,
 * so the admin services fill them in from what a list loads in bulk.
 */
@Mapper(componentModel = "spring")
public interface AdminActivityMapper {

    @Mapping(target = "booking", ignore = true)
    @Mapping(source = "user.name", target = "userName")
    @Mapping(source = "user.phone", target = "userPhone")
    @Mapping(target = "paidInApp", ignore = true)
    @Mapping(target = "cashReceived", ignore = true)
    @Mapping(target = "cashReceivedAt", ignore = true)
    AdminBookingResponse toAdminBooking(Booking booking);

    @Mapping(source = "stadium.id", target = "stadiumId")
    @Mapping(source = "stadium.name", target = "stadiumName")
    @Mapping(source = "creator.id", target = "creatorId")
    @Mapping(source = "creator.name", target = "creatorName")
    @Mapping(target = "teamAName", ignore = true)
    @Mapping(target = "teamBName", ignore = true)
    @Mapping(target = "currentPlayers", ignore = true)
    @Mapping(target = "maxPlayers", ignore = true)
    @Mapping(target = "cancelledAt", ignore = true)
    @Mapping(target = "cancellationReason", ignore = true)
    AdminGameResponse toAdminGame(Game game);

    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.name", target = "userName")
    AdminGamePlayerResponse toPlayer(GameParticipant participant);
}
