package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.GameMessageResponse;
import uz.minipolya.minipolya_backend.entity.GameMessage;

/**
 * GameMessage entity → DTO mapping. A system line has no sender, so {@code userId} and
 * {@code userName} simply stay absent; the sender's side and team are filled in by
 * {@code GameMessageService}, which reads them once for a whole page.
 */
@Mapper(componentModel = "spring")
public interface GameMessageMapper {

    @Mapping(source = "game.id", target = "gameId")
    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.name", target = "userName")
    @Mapping(target = "side", ignore = true)
    @Mapping(target = "teamId", ignore = true)
    @Mapping(target = "teamName", ignore = true)
    @Mapping(target = "teamLevel", ignore = true)
    GameMessageResponse toResponse(GameMessage message);
}
