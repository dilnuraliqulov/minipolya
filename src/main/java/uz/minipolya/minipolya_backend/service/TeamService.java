package uz.minipolya.minipolya_backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.AuditLabels;
import uz.minipolya.minipolya_backend.dto.request.AddTeamMemberRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateTeamRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateTeamRequest;
import uz.minipolya.minipolya_backend.dto.response.TeamDetailResponse;
import uz.minipolya.minipolya_backend.dto.response.TeamInvitePreviewResponse;
import uz.minipolya.minipolya_backend.dto.response.TeamInviteResponse;
import uz.minipolya.minipolya_backend.dto.response.TeamResponse;
import uz.minipolya.minipolya_backend.dto.response.TeamUpcomingGameResponse;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.TeamLevel;
import uz.minipolya.minipolya_backend.entity.TeamMember;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ConflictException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.TeamMapper;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.repository.TeamMemberRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamService {

    private static final String ROLE_LEADER = "leader";
    private static final String ROLE_MEMBER = "member";
    private static final String ALREADY_MEMBER = "User is already a member of this team";
    private static final int INVITE_CODE_MAX = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final UserRepository userRepository;
    private final GameRepository gameRepository;
    private final GameTeamRepository gameTeamRepository;
    private final TeamMapper teamMapper;
    private final AuditService auditService;

    /** Creates a team; the caller becomes the leader and its first member. */
    @Transactional
    public TeamDetailResponse create(UUID userId, CreateTeamRequest request) {
        TeamLevel level = TeamLevel.fromValue(request.getLevel());
        User leader = requireUser(userId);

        Team team = Team.builder()
                .leader(leader)
                .name(request.getName().trim())
                .level(level)
                .build();
        team.addMember(TeamMember.builder().user(leader).role(ROLE_LEADER).build());

        Team saved = teamRepository.save(team);
        log.info("Team created: id={} name='{}' level={} leaderId={}",
                saved.getId(), saved.getName(), level, userId);
        return withGames(saved);
    }

    /**
     * The leader changes how seriously the team plays. One audit entry per real change —
     * picking the level the team already has writes nothing, as every edit in this codebase
     * does. The level never changes what a team may do, only what other teams are told.
     */
    @Transactional
    public TeamDetailResponse updateLevel(UUID requesterId, UUID teamId, UpdateTeamRequest request) {
        TeamLevel level = TeamLevel.fromValue(request.getLevel());
        Team team = requireTeam(teamId);
        requireLeader(team, requesterId);

        TeamLevel before = team.getLevel();
        team.setLevel(level);
        Team saved = teamRepository.save(team);
        if (before != level) {
            auditService.record(AuditEntry.player(requesterId, AuditAction.TEAM_LEVEL_CHANGED)
                    .entity(AuditEntityType.TEAM, saved.getId(), AuditLabels.team(saved))
                    .change("level", before, level));
            log.info("Team level changed: teamId={} {} -> {} by leaderId={}",
                    teamId, before, level, requesterId);
        }
        return withGames(saved);
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
        return withGames(requireTeam(teamId));
    }

    /**
     * The team's own detail plus the two figures its page shows: how many games it has
     * played, and the next one ahead of it. Both are read from the games themselves — the
     * page never counts or guesses anything of its own.
     */
    private TeamDetailResponse withGames(Team team) {
        TeamDetailResponse response = teamMapper.toDetailResponse(team);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        response.setGamesPlayed((int) gameRepository.countPlayedTeamGames(
                team.getId(), now, GameTeam.Status.ACTIVE));
        gameRepository.findUpcomingTeamGames(team.getId(), now, GameTeam.Status.ACTIVE,
                        PageRequest.of(0, 1)).stream().findFirst()
                .ifPresent(game -> response.setUpcomingGame(upcomingGame(team, game)));
        return response;
    }

    /** The next game as its card reads it; the opponent is absent while one is still awaited. */
    private TeamUpcomingGameResponse upcomingGame(Team team, Game game) {
        String opponent = gameTeamRepository.findByGameIdAndStatus(game.getId(), GameTeam.Status.ACTIVE)
                .stream()
                .map(GameTeam::getTeam)
                .filter(other -> other != null && !team.getId().equals(other.getId()))
                .map(Team::getName)
                .findFirst()
                .orElse(null);
        return TeamUpcomingGameResponse.builder()
                .gameId(game.getId())
                .startsAt(game.getStartsAt())
                .status(game.getStatus())
                .stadiumName(game.getStadium() == null ? null : game.getStadium().getName())
                .opponentName(opponent)
                .build();
    }

    @Transactional
    public TeamDetailResponse addMember(UUID requesterId, UUID teamId, AddTeamMemberRequest request) {
        Team team = requireTeam(teamId);
        requireLeader(team, requesterId);

        UUID newMemberId = request.getUserId();
        if (teamMemberRepository.existsByTeamIdAndUserId(teamId, newMemberId)) {
            throw new BadRequestException(ALREADY_MEMBER);
        }
        Team saved = appendMember(team, requireUser(newMemberId));
        log.info("Team member added: teamId={} userId={} by leaderId={}", teamId, newMemberId, requesterId);
        return withGames(saved);
    }

    // ---- Invite links (R32) ----

    /** The team's invite code, created on first request. Leader only. */
    @Transactional
    public TeamInviteResponse getInvite(UUID requesterId, UUID teamId) {
        Team team = requireTeam(teamId);
        requireLeader(team, requesterId);
        if (team.getInviteCode() == null) {
            team.setInviteCode(newInviteCode());
            teamRepository.save(team);
            log.info("Team invite code created: teamId={} by leaderId={}", teamId, requesterId);
        }
        return new TeamInviteResponse(team.getInviteCode());
    }

    /** A new invite code; links with the old one stop working. Leader only. */
    @Transactional
    public TeamInviteResponse renewInvite(UUID requesterId, UUID teamId) {
        Team team = requireTeam(teamId);
        requireLeader(team, requesterId);
        team.setInviteCode(newInviteCode());
        teamRepository.save(team);
        log.info("Team invite code renewed: teamId={} by leaderId={}", teamId, requesterId);
        return new TeamInviteResponse(team.getInviteCode());
    }

    /** What an invite link leads to; any signed-in user. */
    @Transactional(readOnly = true)
    public TeamInvitePreviewResponse previewInvite(UUID userId, String code) {
        Team team = requireInvite(code);
        return new TeamInvitePreviewResponse(teamMapper.toResponse(team),
                teamMemberRepository.existsByTeamIdAndUserId(team.getId(), userId));
    }

    /**
     * Joins the team the code belongs to, as a member — the same membership the leader's
     * add-member gives. Already in it: 409 (the add-member endpoint keeps its 400).
     */
    @Transactional
    public TeamDetailResponse joinByInvite(UUID userId, String code) {
        Team team = requireInvite(code);
        if (teamMemberRepository.existsByTeamIdAndUserId(team.getId(), userId)) {
            throw new ConflictException(ALREADY_MEMBER);
        }
        Team saved = appendMember(team, requireUser(userId));
        log.info("Team joined by invite: teamId={} userId={}", team.getId(), userId);
        return withGames(saved);
    }

    /**
     * The leader removes a member, or a member leaves of their own accord — the same row
     * disappears either way, so it is the same call. Nobody else may remove anybody, and
     * the leader is not a member who can walk out: they delete the team instead.
     */
    @Transactional
    public void removeMember(UUID requesterId, UUID teamId, UUID memberUserId) {
        Team team = requireTeam(teamId);
        boolean leaving = requesterId.equals(memberUserId);
        if (!leaving) {
            requireLeader(team, requesterId);
        }

        if (team.getLeader().getId().equals(memberUserId)) {
            throw new BadRequestException("The leader cannot be removed; delete the team instead");
        }

        TeamMember member = team.getMembers().stream()
                .filter(m -> m.getUser().getId().equals(memberUserId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("User is not a member of this team"));

        team.getMembers().remove(member); // orphanRemoval deletes the row
        teamRepository.save(team);
        if (leaving) {
            log.info("Team left: teamId={} userId={}", teamId, memberUserId);
        } else {
            log.info("Team member removed: teamId={} userId={} by leaderId={}",
                    teamId, memberUserId, requesterId);
        }
    }

    @Transactional
    public void delete(UUID requesterId, UUID teamId) {
        Team team = requireTeam(teamId);
        requireLeader(team, requesterId);
        // games.team_a_id / team_b_id have no ON DELETE action, so deleting a team
        // that plays in a game would fail in the database as an opaque conflict.
        if (gameRepository.existsByTeamAIdOrTeamBId(teamId, teamId)) {
            throw new BadRequestException(
                    "This team is taking part in a game and cannot be deleted");
        }
        teamRepository.delete(team);
        log.info("Team deleted: id={} by leaderId={}", teamId, requesterId);
    }

    // ---- Helpers ----

    private Team appendMember(Team team, User user) {
        team.addMember(TeamMember.builder().user(user).role(ROLE_MEMBER).build());
        return teamRepository.save(team);
    }

    private Team requireInvite(String code) {
        String trimmed = code == null ? "" : code.trim();
        if (trimmed.isEmpty() || trimmed.length() > INVITE_CODE_MAX) {
            throw new ResourceNotFoundException("Invite not found");
        }
        return teamRepository.findByInviteCode(trimmed)
                .orElseThrow(() -> new ResourceNotFoundException("Invite not found"));
    }

    /** 128 random bits, base64url without padding: 22 characters, safe in a link. */
    static String newInviteCode() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

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
