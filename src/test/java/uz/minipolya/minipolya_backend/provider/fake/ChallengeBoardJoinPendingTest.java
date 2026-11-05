package uz.minipolya.minipolya_backend.provider.fake;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.GameTeamRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * R45: a game whose join is in progress (a team {@code joining}, e.g. after a 202
 * PAYMENT_PENDING) is not on the challenge board — to nobody, the joining captain included —
 * and comes back if that join fails; the game says {@code joinPending} meanwhile.
 */
@DatabaseTest
class ChallengeBoardJoinPendingTest extends FakeProviderFlowSupport {

    @Autowired
    private GameTeamRepository gameTeams;

    private org.springframework.test.web.servlet.ResultActions board(User viewer) throws Exception {
        return mvc.perform(get("/api/games/challenges").param("size", "100")
                .header("Authorization", data.bearer(viewer)));
    }

    private GameTeam joiningTeam(UUID gameId) {
        return gameTeams.findByGameId(gameId).stream()
                .filter(t -> t.getStatus() == GameTeam.Status.JOINING).findFirst().orElseThrow();
    }

    @Test
    void aGameWhoseJoinIsPendingIsOffTheBoardForEveryoneAndBackWhenTheJoinFails() throws Exception {
        User leaderA = data.user("Board A");
        User leaderB = data.user("Board B");
        User leaderC = data.user("Board C");
        // 0091: the capture of A's hold gets no clear answer, so B's join stays pending.
        UUID gameId = createTeamGame(leaderA, team(leaderA, "Board Team A"), addCard(leaderA, "8600123412340091"), slot(3, 18));
        board(leaderC).andExpect(jsonPath("$.data.content[*].gameId", hasItem(gameId.toString())));

        join(leaderB, gameId, team(leaderB, "Board Team B"), addCard(leaderB, "8600123412342222"))
                .andExpect(status().isAccepted());

        board(leaderC).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[*].gameId", not(hasItem(gameId.toString()))));
        board(leaderB).andExpect(jsonPath("$.data.content[*].gameId", not(hasItem(gameId.toString()))));
        mvc.perform(get("/api/games/" + gameId).header("Authorization", data.bearer(leaderB)))
                .andExpect(jsonPath("$.data.status").value("open"))
                .andExpect(jsonPath("$.data.joinPending").value(true));

        // The join falls through: the team leaves its side and the game is waiting again.
        GameTeam joining = joiningTeam(gameId);
        joining.setStatus(GameTeam.Status.CANCELLED);
        gameTeams.saveAndFlush(joining);

        board(leaderC).andExpect(jsonPath("$.data.content[*].gameId", hasItem(gameId.toString())));
        mvc.perform(get("/api/games/" + gameId).header("Authorization", data.bearer(leaderC)))
                .andExpect(jsonPath("$.data.joinPending").value(false));
    }

    @Test
    void aCasualGameHasNoJoinPending() throws Exception {
        User creator = data.user("Casual Creator");
        String body = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/games")
                        .header("Authorization", data.bearer(creator))
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"stadiumId\":\"" + stadium.getId() + "\",\"type\":\"casual\",\"maxPlayers\":10,"
                                + "\"startsAt\":\"" + slot(3, 20) + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/games/" + com.jayway.jsonpath.JsonPath.read(body, "$.data.id"))
                        .header("Authorization", data.bearer(creator)))
                .andExpect(jsonPath("$.data.joinPending").doesNotExist());
    }
}
