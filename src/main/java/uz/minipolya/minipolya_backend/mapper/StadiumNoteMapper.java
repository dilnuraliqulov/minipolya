package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import uz.minipolya.minipolya_backend.dto.response.StadiumNoteResponse;
import uz.minipolya.minipolya_backend.entity.StadiumNote;

@Mapper(componentModel = "spring")
public interface StadiumNoteMapper {

    StadiumNoteResponse toResponse(StadiumNote note);
}
