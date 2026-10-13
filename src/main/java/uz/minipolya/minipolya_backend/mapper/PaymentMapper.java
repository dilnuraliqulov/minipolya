package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.PaymentResponse;
import uz.minipolya.minipolya_backend.entity.Payment;

/** Payment entity → DTO mapping. Enum fields copy across unchanged. */
@Mapper(componentModel = "spring")
public interface PaymentMapper {

    @Mapping(source = "booking.id", target = "bookingId")
    @Mapping(source = "game.id", target = "gameId")
    @Mapping(source = "user.id", target = "userId")
    PaymentResponse toResponse(Payment payment);
}
