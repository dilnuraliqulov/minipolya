package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.BookingResponse;
import uz.minipolya.minipolya_backend.entity.Booking;

/**
 * Booking entity → DTO mapping. Builder is disabled so MapStruct uses setters,
 * keeping Lombok's boolean {@code isWalkin} property aligned across types.
 * {@code refundAmount}, {@code cancellationDeadline} and
 * {@code refundIfCancelledNow} are left unset here: they need the booking's
 * payment rows, so {@code BookingResponseFactory} fills them in.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface BookingMapper {

    @Mapping(source = "stadium.id", target = "stadiumId")
    @Mapping(source = "stadium.name", target = "stadiumName")
    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "bookingSeriesId", target = "seriesId")
    @Mapping(source = "stadium.cancellationHours", target = "cancellationPolicy.cancellationHours")
    @Mapping(target = "refundAmount", ignore = true)
    @Mapping(target = "cancellationDeadline", ignore = true)
    @Mapping(target = "refundIfCancelledNow", ignore = true)
    BookingResponse toResponse(Booking booking);
}
