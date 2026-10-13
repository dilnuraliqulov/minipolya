package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.entity.Booking;

/**
 * Booking entity → DTO mapping. Builder is disabled so MapStruct uses setters,
 * keeping Lombok's boolean {@code isWalkin} property aligned across types.
 * {@code refundAmount} is left unset here and filled by the service on a
 * cancellation response.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface BookingMapper {

    @Mapping(source = "stadium.id", target = "stadiumId")
    @Mapping(source = "stadium.name", target = "stadiumName")
    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "stadium.fullRefundHours", target = "cancellationPolicy.fullRefundHours")
    @Mapping(source = "stadium.partialRefundHours", target = "cancellationPolicy.partialRefundHours")
    @Mapping(source = "stadium.partialRefundPercent", target = "cancellationPolicy.partialRefundPercent")
    @Mapping(target = "refundAmount", ignore = true)
    BookingResponse toResponse(Booking booking);
}
