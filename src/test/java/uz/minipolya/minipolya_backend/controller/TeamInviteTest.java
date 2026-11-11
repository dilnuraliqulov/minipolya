package uz.minipolya.minipolya_backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.TeamMember;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.TeamRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R32: team invite links — the leader's code, renewing it, previewing and joining. */
@DatabaseTest
class TeamInviteTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private TeamRepository teamRepository;

    private User leader;
    private String leaderToken;
    private Team team;

    @BeforeEach
    void setUp() {
        leader = data.user("Invite Leader");
        leaderToken = data.bearer(leader);
        team = data.team(leader, "Bars FC");
        team.addMember(TeamMember.builder().user(leader).role("leader").build());
        team = teamRepository.saveAndFlush(team);
    }

    private String code(String token) throws Exception {
        String body = mvc.perform(get("/api/teams/" + team.getId() + "/invite").header("Authorization", token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.code");
    }

    private ResultActions join(String code, String token) throws Exception {
        var request = post("/api/teams/join").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}");
        if (token != null) {
            request.header("Authorization", token);
        }
        return mvc.perform(request);
    }

    @Test
    void theLeadersCodeIsCreatedOnceAndIsUrlSafe() throws Exception {
        String first = code(leaderToken);
        assertThat(first).matches("[A-Za-z0-9_-]{22}");
        assertThat(code(leaderToken)).isEqualTo(first);
        // Never in an ordinary team response.
        mvc.perform(get("/api/teams/" + team.getId()).header("Authorization", leaderToken))
                .andExpect(jsonPath("$.data.inviteCode").doesNotExist());
    }

    @Test
    void renewingKillsTheOldLink() throws Exception {
        String old = code(leaderToken);
        String body = mvc.perform(post("/api/teams/" + team.getId() + "/invite").header("Authorization", leaderToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Invite link renewed"))
                .andReturn().getResponse().getContentAsString();
        String renewed = JsonPath.read(body, "$.data.code");
        assertThat(renewed).isNotEqualTo(old).matches("[A-Za-z0-9_-]{22}");

        String player = data.bearer(data.user("Late Player"));
        mvc.perform(get("/api/teams/invite/" + old).header("Authorization", player))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Invite not found"));
        join(old, player).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Invite not found"));
        join(renewed, player).andExpect(status().isOk());
    }

    @Test
    void previewThenJoin() throws Exception {
        String code = code(leaderToken);
        User player = data.user("New Player");
        String playerToken = data.bearer(player);

        mvc.perform(get("/api/teams/invite/" + code).header("Authorization", playerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.team.id").value(team.getId().toString()))
                .andExpect(jsonPath("$.data.team.name").value("Bars FC"))
                .andExpect(jsonPath("$.data.team.leaderName").value("Invite Leader"))
                .andExpect(jsonPath("$.data.team.memberCount").value(1))
                .andExpect(jsonPath("$.data.isMember").value(false));

        join(code, playerToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Joined team"))
                .andExpect(jsonPath("$.data.id").value(team.getId().toString()))
                .andExpect(jsonPath("$.data.members", hasSize(2)));

        mvc.perform(get("/api/teams/invite/" + code).header("Authorization", playerToken))
                .andExpect(jsonPath("$.data.isMember").value(true));
        mvc.perform(get("/api/teams/me").header("Authorization", playerToken))
                .andExpect(jsonPath("$.data[0].id").value(team.getId().toString()));

        join(code, playerToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("User is already a member of this team"));
        join(code, leaderToken)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("User is already a member of this team"));
    }

    @Test
    void addMemberKeepsIts400() throws Exception {
        User member = data.user("Added Member");
        String body = "{\"userId\":\"" + member.getId() + "\"}";
        mvc.perform(post("/api/teams/" + team.getId() + "/members").header("Authorization", leaderToken)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/teams/" + team.getId() + "/members").header("Authorization", leaderToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("User is already a member of this team"));
    }

    @Test
    void unknownCodesAndBadBodies() throws Exception {
        String player = data.bearer(data.user("Curious Player"));
        mvc.perform(get("/api/teams/invite/nope").header("Authorization", player))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Invite not found"));
        join("nope", player).andExpect(status().isNotFound());
        mvc.perform(post("/api/teams/join").header("Authorization", player)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/teams/join").header("Authorization", player)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void onlyTheLeaderManagesTheCode() throws Exception {
        User member = data.user("Plain Member");
        team.addMember(TeamMember.builder().user(member).role("member").build());
        teamRepository.saveAndFlush(team);
        String memberToken = data.bearer(member);

        mvc.perform(get("/api/teams/" + team.getId() + "/invite").header("Authorization", memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only the team leader can perform this action"));
        mvc.perform(post("/api/teams/" + team.getId() + "/invite").header("Authorization", memberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only the team leader can perform this action"));
        mvc.perform(get("/api/teams/00000000-0000-0000-0000-000000000000/invite").header("Authorization", leaderToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Team not found"));
    }

    @Test
    void everythingNeedsAToken() throws Exception {
        String code = code(leaderToken);
        mvc.perform(get("/api/teams/" + team.getId() + "/invite")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/teams/" + team.getId() + "/invite")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/teams/invite/" + code)).andExpect(status().isUnauthorized());
        join(code, null).andExpect(status().isUnauthorized());
    }
}
