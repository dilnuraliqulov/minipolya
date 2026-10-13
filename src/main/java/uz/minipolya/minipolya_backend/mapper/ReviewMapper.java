package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.ReviewResponse;
import uz.minipolya.minipolya_backend.entity.Review;

/** Review entity → DTO mapping. */
@Mapper(componentModel = "spring")
public interface ReviewMapper {

    @Mapping(source = "stadium.id", target = "stadiumId")
    @Mapping(source = "stadium.name", target = "stadiumName")
    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.name", target = "userName")
    ReviewResponse toResponse(Review review);
}
