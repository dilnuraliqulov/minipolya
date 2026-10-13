package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.GameParticipantResponse;
import uz.minipolya.minipolya_backend.entity.GameParticipant;

/** GameParticipant entity → DTO mapping. */
@Mapper(componentModel = "spring")
public interface GameParticipantMapper {

    @Mapping(source = "game.id", target = "gameId")
    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.name", target = "userName")
    @Mapping(source = "payment.status", target = "paymentStatus")
    GameParticipantResponse toResponse(GameParticipant participant);
}
