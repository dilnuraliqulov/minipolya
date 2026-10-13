package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.GameMessageResponse;
import uz.minipolya.minipolya_backend.entity.GameMessage;

/** GameMessage entity → DTO mapping. */
@Mapper(componentModel = "spring")
public interface GameMessageMapper {

    @Mapping(source = "game.id", target = "gameId")
    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.name", target = "userName")
    GameMessageResponse toResponse(GameMessage message);
}
