package uz.minipolya.minipolya_backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uz.minipolya.minipolya_backend.dto.request.AddTeamMemberRequest;
import uz.minipolya.minipolya_backend.dto.request.CreateTeamRequest;
import uz.minipolya.minipolya_backend.dto.response.TeamDetailResponse;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private TeamMapper teamMapper;

    @InjectMocks
    private TeamService teamService;

    private User user(long id, String name) {
        return User.builder().id(id).name(name).build();
    }

    @Test
    void createMakesCallerLeaderAndFirstMember() {
        User leader = user(10L, "Dilnur");
        when(userRepository.findById(10L)).thenReturn(Optional.of(leader));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));
        TeamDetailResponse dto = new TeamDetailResponse();
        when(teamMapper.toDetailResponse(any(Team.class))).thenReturn(dto);

        TeamDetailResponse result = teamService.create(10L, CreateTeamRequest.builder().name("  Dragons  ").build());

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
        when(userRepository.findById(10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.create(10L, CreateTeamRequest.builder().name("Dragons").build()))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void getDetailThrowsWhenTeamMissing() {
        when(teamRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> teamService.getDetail(1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void addMemberRejectsNonLeader() {
        Team team = Team.builder().id(1L).leader(user(99L, "Owner")).build();
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.addMember(10L, 1L, AddTeamMemberRequest.builder().userId(5L).build()))
                .isInstanceOf(ForbiddenException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void addMemberRejectsDuplicate() {
        Team team = Team.builder().id(1L).leader(user(10L, "Leader")).build();
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.existsByTeamIdAndUserId(1L, 5L)).thenReturn(true);

        assertThatThrownBy(() -> teamService.addMember(10L, 1L, AddTeamMemberRequest.builder().userId(5L).build()))
                .isInstanceOf(BadRequestException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void addMemberAppendsMemberWhenLeaderRequests() {
        User leader = user(10L, "Leader");
        Team team = Team.builder().id(1L).leader(leader).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(teamMemberRepository.existsByTeamIdAndUserId(1L, 5L)).thenReturn(false);
        when(userRepository.findById(5L)).thenReturn(Optional.of(user(5L, "Newbie")));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));
        when(teamMapper.toDetailResponse(any(Team.class))).thenReturn(new TeamDetailResponse());

        teamService.addMember(10L, 1L, AddTeamMemberRequest.builder().userId(5L).build());

        assertThat(team.getMembers()).hasSize(2);
        TeamMember added = team.getMembers().get(1);
        assertThat(added.getUser().getId()).isEqualTo(5L);
        assertThat(added.getRole()).isEqualTo("member");
    }

    @Test
    void removeMemberDeletesTheMemberRow() {
        User leader = user(10L, "Leader");
        User member = user(5L, "Member");
        Team team = Team.builder().id(1L).leader(leader).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        team.addMember(TeamMember.builder().user(member).role("member").build());
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));
        when(teamRepository.save(any(Team.class))).thenAnswer(inv -> inv.getArgument(0));

        teamService.removeMember(10L, 1L, 5L);

        assertThat(team.getMembers()).hasSize(1);
        assertThat(team.getMembers().get(0).getUser().getId()).isEqualTo(10L);
        verify(teamRepository).save(team);
    }

    @Test
    void removeMemberRejectsRemovingTheLeader() {
        User leader = user(10L, "Leader");
        Team team = Team.builder().id(1L).leader(leader).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.removeMember(10L, 1L, 10L))
                .isInstanceOf(BadRequestException.class);

        verify(teamRepository, never()).save(any());
    }

    @Test
    void removeMemberThrowsWhenUserNotInTeam() {
        User leader = user(10L, "Leader");
        Team team = Team.builder().id(1L).leader(leader).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.removeMember(10L, 1L, 77L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deleteRejectsNonLeader() {
        Team team = Team.builder().id(1L).leader(user(99L, "Owner")).build();
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));

        assertThatThrownBy(() -> teamService.delete(10L, 1L))
                .isInstanceOf(ForbiddenException.class);

        verify(teamRepository, never()).delete(any());
    }

    @Test
    void deleteRemovesTeamWhenLeaderRequests() {
        Team team = Team.builder().id(1L).leader(user(10L, "Leader")).build();
        when(teamRepository.findById(1L)).thenReturn(Optional.of(team));

        teamService.delete(10L, 1L);

        verify(teamRepository).delete(team);
    }

    @Test
    void listMineReturnsEveryTeamTheUserBelongsTo() {
        User me = user(10L, "Me");
        Team a = Team.builder().id(1L).leader(me).name("A").build();
        Team b = Team.builder().id(2L).leader(user(20L, "Other")).name("B").build();
        when(teamMemberRepository.findByUserId(10L)).thenReturn(List.of(
                TeamMember.builder().team(a).user(me).role("leader").build(),
                TeamMember.builder().team(b).user(me).role("member").build()));

        teamService.listMine(10L);

        verify(teamMapper).toResponse(a);
        verify(teamMapper).toResponse(b);
    }
}
