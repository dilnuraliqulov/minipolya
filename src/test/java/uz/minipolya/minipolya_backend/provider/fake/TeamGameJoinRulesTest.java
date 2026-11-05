package uz.minipolya.minipolya_backend.provider.fake;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.GameTeam;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Joining a team_vs_team game: the server decides the side, and only the joining team's
 * leader may join — always paying. Nobody joins the opposing side for free.
 */
@DatabaseTest
class TeamGameJoinRulesTest extends FakeProviderFlowSupport {

    @org.springframework.beans.factory.annotation.Autowired
    private uz.minipolya.minipolya_backend.repository.GameTeamRepository gameTeams;
    @org.springframework.beans.factory.annotation.Autowired
    private uz.minipolya.minipolya_backend.repository.GameParticipantRepository participants;

    private ResultActions joinWith(User user, UUID gameId, String body) throws Exception {
        return mvc.perform(post("/api/games/" + gameId + "/join").header("Authorization", data.bearer(user))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private UUID waitingGame(User leaderA) throws Exception {
        UUID cardA = addCard(leaderA, "8600123412341111");
        return createTeamGame(leaderA, team(leaderA, "Rules A " + UUID.randomUUID()), cardA, slot(3, 18));
    }

    @Test
    void aLeaderWhoSendsNoSideJoinsAsSideBAndPays() throws Exception {
        User leaderB = data.user("No Side B");
        UUID gameId = waitingGame(data.user("No Side A"));
        UUID teamB = team(leaderB, "No Side Team B");
        UUID cardB = addCard(leaderB, "8600123412342222");

        joinWith(leaderB, gameId, "{\"gameId\":\"" + gameId + "\",\"teamId\":\"" + teamB + "\",\"cardId\":\"" + cardB + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.side").value("b"));

        assertThat(games.findById(gameId).orElseThrow().getStatus()).isEqualTo("confirmed");
        GameTeam joined = gameTeams.findByGameIdAndStatus(gameId, GameTeam.Status.ACTIVE).stream()
                .filter(t -> t.getSide() == GameParticipant.Side.B).findFirst().orElseThrow();
        assertThat(payments.findByGameTeamId(joined.getId())).extracting(Payment::getStatus)
                .containsExactly(Payment.Status.PAID);
        assertThat(balance(cardB)).isLessThan(START);
    }

    @Test
    void anOldClientSendingSideBStillJoinsAndPays() throws Exception {
        User leaderB = data.user("Legacy B");
        UUID gameId = waitingGame(data.user("Legacy A"));
        UUID teamB = team(leaderB, "Legacy Team B");
        UUID cardB = addCard(leaderB, "8600123412342223");

        joinWith(leaderB, gameId, "{\"gameId\":\"" + gameId + "\",\"side\":\"b\",\"teamId\":\"" + teamB
                + "\",\"cardId\":\"" + cardB + "\"}")
                .andExpect(status().isOk());

        assertThat(games.findById(gameId).orElseThrow().getStatus()).isEqualTo("confirmed");
        assertThat(balance(cardB)).isLessThan(START);
    }

    @Test
    void aSideThatContradictsTheServerIs400() throws Exception {
        User leaderB = data.user("Side A B");
        UUID gameId = waitingGame(data.user("Side A A"));
        UUID teamB = team(leaderB, "Side A Team B");
        UUID cardB = addCard(leaderB, "8600123412342224");

        joinWith(leaderB, gameId, "{\"gameId\":\"" + gameId + "\",\"side\":\"a\",\"teamId\":\"" + teamB
                + "\",\"cardId\":\"" + cardB + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The opposing team joins as side b"));

        assertThat(games.findById(gameId).orElseThrow().getStatus()).isEqualTo("open");
        assertThat(balance(cardB)).isEqualTo(START);
        assertThat(participants.findByGameIdAndUserId(gameId, leaderB.getId())).isEmpty();
    }

    @Test
    void aPlainMemberCannotJoinATeamGameAtAll() throws Exception {
        User leaderB = data.user("Member Leader B");
        User member = data.user("Plain Member");
        UUID gameId = waitingGame(data.user("Member A"));
        UUID teamB = team(leaderB, "Member Team B");
        mvc.perform(post("/api/teams/" + teamB + "/members").header("Authorization", data.bearer(leaderB))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"userId\":\"" + member.getId() + "\"}"))
                .andExpect(status().isOk());
        UUID memberCard = addCard(member, "8600123412342225");

        joinWith(member, gameId, "{\"gameId\":\"" + gameId + "\",\"teamId\":\"" + teamB + "\",\"cardId\":\"" + memberCard + "\"}")
                .andExpect(status().isForbidden());
        joinWith(member, gameId, "{\"gameId\":\"" + gameId + "\"}")
                .andExpect(status().isForbidden());
        joinWith(member, gameId, "{\"gameId\":\"" + gameId + "\",\"side\":\"b\"}")
                .andExpect(status().isForbidden());

        assertThat(participants.findByGameIdAndUserId(gameId, member.getId())).isEmpty();
        assertThat(games.findById(gameId).orElseThrow().getStatus()).isEqualTo("open");
    }

    @Test
    void aLeaderWithoutACardIs400() throws Exception {
        User leaderB = data.user("No Card B");
        UUID gameId = waitingGame(data.user("No Card A"));
        UUID teamB = team(leaderB, "No Card Team B");

        joinWith(leaderB, gameId, "{\"gameId\":\"" + gameId + "\",\"teamId\":\"" + teamB + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("cardId is required to join a team game"));

        assertThat(participants.findByGameIdAndUserId(gameId, leaderB.getId())).isEmpty();
        assertThat(games.findById(gameId).orElseThrow().getStatus()).isEqualTo("open");
    }
}
