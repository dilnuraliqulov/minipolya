package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.TeamMember;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R14: a user's teams, and an owner's stadiums. */
@DatabaseTest
class AdminUserTeamsTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private TeamRepository teamRepository;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = data.bearer(data.admin("Teams Admin"));
    }

    /** A team as TeamService makes it: the leader is its first member. */
    private Team team(User leader, String name, User... members) {
        Team team = Team.builder().leader(leader).name(name).build();
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        for (User member : members) {
            team.addMember(TeamMember.builder().user(member).role("member").build());
        }
        return teamRepository.save(team);
    }

    @Test
    void aUsersTeamsWithTheirRoleInEach() throws Exception {
        User aziz = data.user("Aziz");
        User bobur = data.user("Bobur");
        team(aziz, "Bunyodkor", bobur);
        team(bobur, "Lokomotiv", aziz, data.user("Third"));
        team(data.user("Stranger"), "Unrelated");

        mvc.perform(get("/api/admin/users/" + aziz.getId() + "/teams").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].name").value("Bunyodkor"))
                .andExpect(jsonPath("$.data[0].role").value("leader"))
                .andExpect(jsonPath("$.data[0].leaderName").value("Aziz"))
                .andExpect(jsonPath("$.data[0].memberCount").value(2))
                .andExpect(jsonPath("$.data[1].name").value("Lokomotiv"))
                .andExpect(jsonPath("$.data[1].role").value("member"))
                .andExpect(jsonPath("$.data[1].leaderName").value("Bobur"))
                .andExpect(jsonPath("$.data[1].memberCount").value(3));
    }

    @Test
    void noTeamsIsAnEmptyList() throws Exception {
        mvc.perform(get("/api/admin/users/" + data.user("Loner").getId() + "/teams").header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void badIdsAre400Or404AndOnlyAdminsMayAsk() throws Exception {
        mvc.perform(get("/api/admin/users/not-an-id/teams").header("Authorization", adminToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid id"));
        mvc.perform(get("/api/admin/users/" + UUID.randomUUID() + "/teams").header("Authorization", adminToken))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("User not found"));
        User player = data.user("Curious");
        mvc.perform(get("/api/admin/users/" + player.getId() + "/teams")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users/" + player.getId() + "/teams").header("Authorization", data.bearer(player)))
                .andExpect(status().isForbidden());
    }

    @Test
    void theStadiumListFiltersByOwner() throws Exception {
        User owner = data.owner("Many Pitches");
        data.stadium(owner, "Pitch One", "approved");
        data.stadium(owner, "Pitch Two", "pending");
        data.stadium(data.owner("Someone Else"), "Not Theirs");

        mvc.perform(get("/api/admin/stadiums").header("Authorization", adminToken)
                        .param("ownerId", owner.getId().toString()))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].ownerId").value(owner.getId().toString()));
        mvc.perform(get("/api/admin/stadiums").header("Authorization", adminToken).param("ownerId", "nope"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid id"));
    }
}
