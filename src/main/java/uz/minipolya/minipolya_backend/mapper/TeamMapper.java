package uz.minipolya.minipolya_backend.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import uz.minipolya.minipolya_backend.dto.response.TeamDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.TeamMemberResponse;
import uz.minipolya.minipolya_backend.dto.response.TeamResponse;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.TeamMember;

/**
 * Team entity → DTO mapping. The member list on {@link TeamDetailResponse} is
 * mapped element-wise via {@link #toMemberResponse(TeamMember)}.
 */
@Mapper(componentModel = "spring")
public interface TeamMapper {

    @Mapping(source = "leader.name", target = "leaderName")
    @Mapping(target = "memberCount", expression = "java(team.getMembers() == null ? 0 : team.getMembers().size())")
    TeamResponse toResponse(Team team);

    @Mapping(source = "leader.id", target = "leaderId")
    @Mapping(source = "leader.name", target = "leaderName")
    TeamDetailResponse toDetailResponse(Team team);

    @Mapping(source = "user.id", target = "userId")
    @Mapping(source = "user.name", target = "userName")
    TeamMemberResponse toMemberResponse(TeamMember member);
}
