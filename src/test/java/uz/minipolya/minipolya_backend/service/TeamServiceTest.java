package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.audit.Audited;
import uz.minipolya.minipolya_backend.dto.request.AddTeamMemberRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateTeamRequest;
import uz.minipolya.minipolya_backend.dto.request.UpdateTeamRequest;
import uz.minipolya.minipolya_backend.dto.response.TeamDetailResponse;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.TeamLevel;
import uz.minipolya.minipolya_backend.entity.TeamMember;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.exception.BadRequestException;
import uz.minipolya.minipolya_backend.exception.ForbiddenException;
import uz.minipolya.minipolya_backend.exception.ResourceNotFoundException;
import uz.minipolya.minipolya_backend.mapper.TeamMapper;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.repository.TeamMemberRepository;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.repository.UserRepository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeamServiceTest {

    @Mock
    private TeamRepository teamRepository;
    @Mock
    private TeamMemberRepository teamMemberRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private GameRepository gameRepository;
    @Mock
    private GameTeamRepository gameTeamRepository;
    @Mock
    private TeamMapper teamMapper;
    @Mock
    private AuditService auditService;

    @InjectMocks
    private TeamService teamService;

    private static final UUID LEADER_ID = UUID.randomUUID();
    private static final UUID MEMBER_ID = UUID.randomUUID();
    private static final UUID TEAM_ID = UUID.randomUUID();
    private static final UUID OWNER_ID = UUID.randomUUID();
    private static final UUID OTHER_ID = UUID.randomUUID();
    private static final UUID STRANGER_ID = UUID.randomUUID();
    private static final UUID TEAM_B_ID = UUID.randomUUID();
    private static final UUID GAME_ID = UUID.randomUUID();

    private User user(UUID id, String name) {
        return User.builder().id(id).name(name).build();
    }

    @Test
    void createMakesCallerLeaderAndFirstMember() {
        User leader = user(LEADER_ID, "Dilnur");
        when(userRepository.findById(LEADER_ID)).thenReturn(Optional.of(leader));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));
        TeamDetailResponse dto = new TeamDetailResponse();
        when(teamMapper.toDetailResponse(any(Team.class))).thenReturn(dto);

        TeamDetailResponse result = teamService.create(LEADER_ID, CreateTeamRequest.builder().name("  Dragons  ").level("orta").build());

        assertThat(result).isSameAs(dto);
        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository).save(captor.capture());
        Team saved = captor.getValue();
        assertThat(saved.getLeader()).isSameAs(leader);
        assertThat(saved.getName()).isEqualTo("Dragons");
        assertThat(saved.getMembers()).hasSize(1);
        TeamMember first = saved.getMembers().get(0);
        assertThat(first.getRole()).isEqualTo("leader");
        assertThat(first.getUser()).isSameAs(leader);
        assertThat(first.getTeam()).isSameAs(saved);
    }

    @Test
    void createThrowsWhenUserMissing() {
        when(userRepository.findById(LEADER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.create(LEADER_ID, CreateTeamRequest.builder().name("Dragons").level("orta").build()))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void getDetailThrowsWhenTeamMissing() {
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.getDetail(TEAM_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addMemberRejectsNonLeader() {
        Team team = Team.builder().id(TEAM_ID).leader(user(OWNER_ID, "Owner")).build();
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.addMember(LEADER_ID, TEAM_ID, AddTeamMemberRequest.builder().userId(MEMBER_ID).build()))
                .isInstanceOf(ForbiddenException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void addMemberRejectsDuplicate() {
        Team team = Team.builder().id(TEAM_ID).leader(user(LEADER_ID, "Leader")).build();
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, MEMBER_ID)).thenReturn(true);

        assertThatThrownBy(() -> teamService.addMember(LEADER_ID, TEAM_ID, AddTeamMemberRequest.builder().userId(MEMBER_ID).build()))
                .isInstanceOf(BadRequestException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void addMemberAppendsMemberWhenLeaderRequests() {
        User leader = user(LEADER_ID, "Leader");
        Team team = Team.builder().id(TEAM_ID).leader(leader).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.existsByTeamIdAndUserId(TEAM_ID, MEMBER_ID)).thenReturn(false);
        when(userRepository.findById(MEMBER_ID)).thenReturn(Optional.of(user(MEMBER_ID, "Newbie")));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));
        when(teamMapper.toDetailResponse(any(Team.class))).thenReturn(new TeamDetailResponse());

        teamService.addMember(LEADER_ID, TEAM_ID, AddTeamMemberRequest.builder().userId(MEMBER_ID).build());

        assertThat(team.getMembers()).hasSize(2);
        TeamMember added = team.getMembers().get(1);
        assertThat(added.getUser().getId()).isEqualTo(MEMBER_ID);
        assertThat(added.getRole()).isEqualTo("member");
    }

    @Test
    void removeMemberDeletesTheMemberRow() {
        User leader = user(LEADER_ID, "Leader");
        User member = user(MEMBER_ID, "Member");
        Team team = Team.builder().id(TEAM_ID).leader(leader).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        team.addMember(TeamMember.builder().user(member).role("member").build());
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));

        teamService.removeMember(LEADER_ID, TEAM_ID, MEMBER_ID);

        assertThat(team.getMembers()).hasSize(1);
        assertThat(team.getMembers().get(0).getUser().getId()).isEqualTo(LEADER_ID);
        verify(teamRepository).save(team);
    }

    @Test
    void removeMemberRejectsRemovingTheLeader() {
        User leader = user(LEADER_ID, "Leader");
        Team team = Team.builder().id(TEAM_ID).leader(leader).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.removeMember(LEADER_ID, TEAM_ID, LEADER_ID))
                .isInstanceOf(BadRequestException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void removeMemberThrowsWhenUserNotInTeam() {
        User leader = user(LEADER_ID, "Leader");
        Team team = Team.builder().id(TEAM_ID).leader(leader).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.removeMember(LEADER_ID, TEAM_ID, STRANGER_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteRejectsNonLeader() {
        Team team = Team.builder().id(TEAM_ID).leader(user(OWNER_ID, "Owner")).build();
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.delete(LEADER_ID, TEAM_ID))
                .isInstanceOf(ForbiddenException.class);

        verify(teamRepository, never()).delete(any());
    }

    @Test
    void deleteRemovesTeamWhenLeaderRequests() {
        Team team = Team.builder().id(TEAM_ID).leader(user(LEADER_ID, "Leader")).build();
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(gameRepository.existsByTeamAIdOrTeamBId(TEAM_ID, TEAM_ID)).thenReturn(false);

        teamService.delete(LEADER_ID, TEAM_ID);

        verify(teamRepository).delete(team);
    }

    @Test
    void deleteRejectsATeamThatIsTakingPartInAGame() {
        Team team = Team.builder().id(TEAM_ID).leader(user(LEADER_ID, "Leader")).build();
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(gameRepository.existsByTeamAIdOrTeamBId(TEAM_ID, TEAM_ID)).thenReturn(true);

        assertThatThrownBy(() -> teamService.delete(LEADER_ID, TEAM_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("taking part in a game");

        verify(teamRepository, never()).delete(any());
    }

    @Test
    void listMineReturnsEveryTeamTheUserBelongsTo() {
        User me = user(LEADER_ID, "Me");
        Team a = Team.builder().id(TEAM_ID).leader(me).name("A").build();
        Team b = Team.builder().id(TEAM_B_ID).leader(user(OTHER_ID, "Other")).name("B").build();
        when(teamMemberRepository.findByUserId(LEADER_ID)).thenReturn(List.of(
                TeamMember.builder().team(a).user(me).role("leader").build(),
                TeamMember.builder().team(b).user(me).role("member").build()));

        teamService.listMine(LEADER_ID);

        verify(teamMapper).toResponse(a);
        verify(teamMapper).toResponse(b);
    }

    // ---- The team's level ----

    @Test
    void createStoresTheChosenLevel() {
        User leader = user(LEADER_ID, "Dilnur");
        when(userRepository.findById(LEADER_ID)).thenReturn(Optional.of(leader));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));
        when(teamMapper.toDetailResponse(any(Team.class))).thenReturn(new TeamDetailResponse());

        teamService.create(LEADER_ID,
                CreateTeamRequest.builder().name("Dragons").level("professional").build());

        ArgumentCaptor<Team> captor = ArgumentCaptor.forClass(Team.class);
        verify(teamRepository).save(captor.capture());
        assertThat(captor.getValue().getLevel()).isEqualTo(TeamLevel.PROFESSIONAL);
    }

    @Test
    void createRejectsAnUnknownLevel() {
        assertThatThrownBy(() -> teamService.create(LEADER_ID,
                CreateTeamRequest.builder().name("Dragons").level("elite").build()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("level");

        verify(teamRepository, never()).save(any());
    }

    @Test
    void aTeamFromBeforeLevelsHasNone() {
        Team team = Team.builder().id(TEAM_ID).leader(user(LEADER_ID, "Leader")).name("Old").build();

        assertThat(team.getLevel()).isNull();
    }

    @Test
    void onlyTheLeaderEditsTheLevel() {
        Team team = Team.builder().id(TEAM_ID).leader(user(OWNER_ID, "Owner")).name("Dragons").build();
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.updateLevel(LEADER_ID, TEAM_ID,
                UpdateTeamRequest.builder().level("orta").build()))
                .isInstanceOf(ForbiddenException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void updateLevelAuditsTheChange() {
        Team team = Team.builder().id(TEAM_ID).leader(user(LEADER_ID, "Leader")).name("Dragons")
                .level(TeamLevel.HAVASKOR).build();
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));
        when(teamMapper.toDetailResponse(any(Team.class))).thenReturn(new TeamDetailResponse());

        teamService.updateLevel(LEADER_ID, TEAM_ID,
                UpdateTeamRequest.builder().level("professional").build());

        assertThat(team.getLevel()).isEqualTo(TeamLevel.PROFESSIONAL);
        AuditEntry entry = Audited.one(auditService, AuditAction.TEAM_LEVEL_CHANGED);
        assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.TEAM);
        assertThat(entry.getEntityId()).isEqualTo(TEAM_ID);
        assertThat(entry.getEntityLabel()).isEqualTo("Dragons");
        assertThat(entry.getChanges()).singleElement().satisfies(change -> {
            assertThat(change.field()).isEqualTo("level");
            assertThat(change.oldValue()).isEqualTo("havaskor");
            assertThat(change.newValue()).isEqualTo("professional");
        });
    }

    @Test
    void updateLevelToTheSameValueWritesNoEntry() {
        Team team = Team.builder().id(TEAM_ID).leader(user(LEADER_ID, "Leader")).name("Dragons")
                .level(TeamLevel.ORTA).build();
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));
        when(teamMapper.toDetailResponse(any(Team.class))).thenReturn(new TeamDetailResponse());

        teamService.updateLevel(LEADER_ID, TEAM_ID, UpdateTeamRequest.builder().level("orta").build());

        assertThat(Audited.entries(auditService, AuditAction.TEAM_LEVEL_CHANGED)).isEmpty();
    }

    // ---- What the team page shows: the next game and the games played ----

    private Team teamWithLeader() {
        User leader = user(LEADER_ID, "Leader");
        Team team = Team.builder().id(TEAM_ID).leader(leader).name("Dragons").build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMapper.toDetailResponse(team)).thenReturn(new TeamDetailResponse());
        return team;
    }

    private Game teamGame(String status, OffsetDateTime startsAt) {
        return Game.builder().id(GAME_ID).type("team_vs_team").status(status).startsAt(startsAt)
                .stadium(Stadium.builder().id(UUID.randomUUID()).name("Chilonzor Arena").build())
                .build();
    }

    @Test
    void theDetailCountsTheGamesTheTeamHasPlayed() {
        teamWithLeader();
        when(gameRepository.countPlayedTeamGames(eq(TEAM_ID), any(), eq(GameTeam.Status.ACTIVE)))
                .thenReturn(4L);

        TeamDetailResponse detail = teamService.getDetail(TEAM_ID);

        assertThat(detail.getGamesPlayed()).isEqualTo(4);
    }

    @Test
    void theDetailNamesTheNextGamesOpponent() {
        Team team = teamWithLeader();
        OffsetDateTime starts = OffsetDateTime.now(ZoneOffset.UTC).plusDays(2);
        Game game = teamGame("confirmed", starts);
        when(gameRepository.findUpcomingTeamGames(eq(TEAM_ID), any(), eq(GameTeam.Status.ACTIVE), any()))
                .thenReturn(List.of(game));
        when(gameTeamRepository.findByGameIdAndStatus(GAME_ID, GameTeam.Status.ACTIVE)).thenReturn(List.of(
                GameTeam.builder().team(team).build(),
                GameTeam.builder().team(Team.builder().id(TEAM_B_ID).name("Delta FC").build()).build()));

        TeamDetailResponse detail = teamService.getDetail(TEAM_ID);

        assertThat(detail.getUpcomingGame()).isNotNull();
        assertThat(detail.getUpcomingGame().getGameId()).isEqualTo(GAME_ID);
        assertThat(detail.getUpcomingGame().getStartsAt()).isEqualTo(starts);
        assertThat(detail.getUpcomingGame().getStatus()).isEqualTo("confirmed");
        assertThat(detail.getUpcomingGame().getStadiumName()).isEqualTo("Chilonzor Arena");
        assertThat(detail.getUpcomingGame().getOpponentName()).isEqualTo("Delta FC");
    }

    @Test
    void theDetailSaysNothingWhileTheOpponentIsStillAwaited() {
        Team team = teamWithLeader();
        Game game = teamGame("open", OffsetDateTime.now(ZoneOffset.UTC).plusDays(1));
        when(gameRepository.findUpcomingTeamGames(eq(TEAM_ID), any(), eq(GameTeam.Status.ACTIVE), any()))
                .thenReturn(List.of(game));
        when(gameTeamRepository.findByGameIdAndStatus(GAME_ID, GameTeam.Status.ACTIVE))
                .thenReturn(List.of(GameTeam.builder().team(team).build()));

        TeamDetailResponse detail = teamService.getDetail(TEAM_ID);

        assertThat(detail.getUpcomingGame().getOpponentName()).isNull();
        assertThat(detail.getUpcomingGame().getStatus()).isEqualTo("open");
    }

    @Test
    void noUpcomingGameLeavesTheFieldOut() {
        teamWithLeader();
        when(gameRepository.findUpcomingTeamGames(eq(TEAM_ID), any(), eq(GameTeam.Status.ACTIVE), any()))
                .thenReturn(List.of());

        assertThat(teamService.getDetail(TEAM_ID).getUpcomingGame()).isNull();
    }

    // ---- Leaving a team ----

    @Test
    void aMemberMayLeaveTheTeam() {
        User leader = user(LEADER_ID, "Leader");
        User member = user(MEMBER_ID, "Member");
        Team team = Team.builder().id(TEAM_ID).leader(leader).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        team.addMember(TeamMember.builder().user(member).role("member").build());
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));

        teamService.removeMember(MEMBER_ID, TEAM_ID, MEMBER_ID);

        assertThat(team.getMembers()).hasSize(1);
    }

    @Test
    void aMemberMayNotRemoveAnotherMember() {
        User leader = user(LEADER_ID, "Leader");
        Team team = Team.builder().id(TEAM_ID).leader(leader).build();
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.removeMember(MEMBER_ID, TEAM_ID, STRANGER_ID))
                .isInstanceOf(ForbiddenException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void theLeaderCannotLeaveTheirOwnTeam() {
        User leader = user(LEADER_ID, "Leader");
        Team team = Team.builder().id(TEAM_ID).leader(leader).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        when(teamRepository.findById(TEAM_ID)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.removeMember(LEADER_ID, TEAM_ID, LEADER_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("delete the team instead");
    }
}
