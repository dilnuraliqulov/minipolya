package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Builder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.GameDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.GameResponse;
import uz.minipolya.minipolya_backend.entity.Game;

/**
 * Game entity → DTO mapping. Builder is disabled so MapStruct uses setters,
 * keeping Lombok's boolean {@code isPublic} property aligned across types.
 * Participant-derived fields ({@code currentPlayers}, {@code participants})
 * are left unset here — the service fills them in step 10.
 */
@Mapper(componentModel = "spring", builder = @Builder(disableBuilder = true))
public interface GameMapper {

    @Mapping(source = "stadium.name", target = "stadiumName")
    @Mapping(source = "creator.name", target = "creatorName")
    @Mapping(target = "currentPlayers", ignore = true)
    GameResponse toResponse(Game game);

    @Mapping(source = "stadium.id", target = "stadiumId")
    @Mapping(source = "stadium.name", target = "stadiumName")
    @Mapping(source = "creator.id", target = "creatorId")
    @Mapping(source = "creator.name", target = "creatorName")
    @Mapping(source = "teamA.id", target = "teamAId")
    @Mapping(source = "teamA.name", target = "teamAName")
    @Mapping(source = "teamB.id", target = "teamBId")
    @Mapping(source = "teamB.name", target = "teamBName")
    @Mapping(target = "currentPlayers", ignore = true)
    @Mapping(target = "participants", ignore = true)
    GameDetailResponse toDetailResponse(Game game);
}
