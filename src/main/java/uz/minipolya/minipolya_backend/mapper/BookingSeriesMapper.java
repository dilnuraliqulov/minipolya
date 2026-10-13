package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.BookingSeriesResponse;
import uz.minipolya.minipolya_backend.entity.BookingSeries;

/**
 * BookingSeries entity → DTO mapping. Builder disabled so MapStruct uses setters
 * (aligns the boolean {@code isActive} property). {@code nextBookingDate} is set
 * by the service.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface BookingSeriesMapper {

    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.name", target = "userName")
    @Mapping(source = "stadium.id", target = "stadiumId")
    @Mapping(source = "stadium.name", target = "stadiumName")
    @Mapping(source = "durationHours", target = "duration")
    @Mapping(target = "nextBookingDate", ignore = true)
    BookingSeriesResponse toResponse(BookingSeries series);
}
