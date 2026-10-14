package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.dto.request.AddTeamMemberRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateTeamRequest;
import uz.minipolya.minipolya_backend.dto.response.TeamDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.TeamResponse;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.TeamMember;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.TeamMapper;
import uz.minipolya.minipolya_backend.repository.TeamMemberRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamService {

    private static final String ROLE_LEADER = "leader";
    private static final String ROLE_MEMBER = "member";

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final TeamMapper teamMapper;

    /** Creates a team; the caller becomes the leader and its first member. */
    @Transactional
    public TeamDetailResponse create(UUID userId, CreateTeamRequest request) {
        User leader = requireUser(userId);

        Team team = Team.builder()
                .leader(leader)
                .name(request.getName().trim())
                .build();
        team.addMember(TeamMember.builder().user(leader).role(ROLE_LEADER).build());

        Team saved = teamRepository.save(team);
        log.info("Team created: id={} name='{}' leaderId={}", saved.getId(), saved.getName(), userId);
        return teamMapper.toDetailResponse(saved);
    }

    /** All teams the user belongs to, whether as leader or member. */
    @Transactional(readOnly = true)
    public List<TeamResponse> listMine(UUID userId) {
        return teamMemberRepository.findByUserId(userId).stream()
                .map(TeamMember::getTeam)
                .map(teamMapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public TeamDetailResponse getDetail(UUID teamId) {
        return teamMapper.toDetailResponse(requireTeam(teamId));
    }

    @Transactional
    public TeamDetailResponse addMember(UUID requesterId, UUID teamId, AddTeamMemberRequest request) {
        Team team = requireTeam(teamId);
        requireLeader(team, requesterId);

        UUID newMemberId = request.getUserId();
        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, newMemberId)) {
            throw new BadRequestException("User is already a member of this team");
        }
        User user = requireUser(newMemberId);

        team.addMember(TeamMember.builder().user(user).role(ROLE_MEMBER).build());
        Team saved = teamRepository.save(team);
        log.info("Team member added: teamId={} userId={} by leaderId={}", teamId, newMemberId, requesterId);
        return teamMapper.toDetailResponse(saved);
    }

    @Transactional
    public void removeMember(UUID requesterId, UUID teamId, UUID memberUserId) {
        Team team = requireTeam(teamId);
        requireLeader(team, requesterId);

        if (team.getLeader().getId().equals(memberUserId)) {
            throw new BadRequestException("The leader cannot be removed; delete the team instead");
        }

        TeamMember member = team.getMembers().stream()
                .filter(m -> m.getUser().getId().equals(memberUserId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("User is not a member of this team"));

        team.getMembers().remove(member); // orphanRemoval deletes the row
        teamRepository.save(team);
        log.info("Team member removed: teamId={} userId={} by leaderId={}", teamId, memberUserId, requesterId);
    }

    @Transactional
    public void delete(UUID requesterId, UUID teamId) {
        Team team = requireTeam(teamId);
        requireLeader(team, requesterId);
        teamRepository.delete(team);
        log.info("Team deleted: id={} by leaderId={}", teamId, requesterId);
    }

    // ---- Helpers ----

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private Team requireTeam(UUID teamId) {
        return teamRepository.findById(teamId)
                .orElseThrow(() -> new ResourceNotFoundException("Team not found"));
    }

    private void requireLeader(Team team, UUID requesterId) {
        if (!team.getLeader().getId().equals(requesterId)) {
            throw new ForbiddenException("Only the team leader can perform this action");
        }
    }
}
