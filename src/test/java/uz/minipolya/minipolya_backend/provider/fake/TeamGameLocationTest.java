package uz.minipolya.minipolya_backend.provider.fake;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.QueryCounter;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A team game shows where it is played to everyone who can open it, in every state: the
 * game detail and the challenge board carry the stadium's {@code location} (address,
 * district, Moʻljal, coordinates, cover photo, format), and the board loads the stadiums
 * with its page rather than one per card.
 */
@DatabaseTest
class TeamGameLocationTest extends FakeProviderFlowSupport {

    @Autowired
    private QueryCounter queries;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private void locate(Stadium s, String district, String landmark) {
        s.setDistrict(district);
        s.setLandmark(landmark);
        s.setLatitude(new BigDecimal("41.2856800"));
        s.setLongitude(new BigDecimal("69.2034600"));
        stadiums.saveAndFlush(s);
    }

    private ResultActions detail(UUID gameId, User viewer) throws Exception {
        return mvc.perform(get("/api/games/" + gameId).header("Authorization", data.bearer(viewer)))
                .andExpect(status().isOk());
    }

    private static void expectLocation(ResultActions result, String prefix, Stadium s) throws Exception {
        result.andExpect(jsonPath(prefix + ".id").value(s.getId().toString()))
                .andExpect(jsonPath(prefix + ".name").value("Flow Arena"))
                .andExpect(jsonPath(prefix + ".address").value("Toshkent, Flow Arena"))
                .andExpect(jsonPath(prefix + ".district").value("Chilonzor"))
                .andExpect(jsonPath(prefix + ".landmark").value("Metro yonida"))
                .andExpect(jsonPath(prefix + ".latitude").value(41.28568))
                .andExpect(jsonPath(prefix + ".longitude").value(69.20346))
                .andExpect(jsonPath(prefix + ".format").value("5x5"))
                .andExpect(jsonPath(prefix + ".coverPhotoUrl").value(startsWith("/api/uploads/stadiums/")));
    }

    @Test
    void everyoneSeesWhereTheGameIsInEveryState() throws Exception {
        stadium = data.photos(stadium, 1);
        locate(stadium, "Chilonzor", "Metro yonida");
        User leaderA = data.user("Loc A");
        User leaderB = data.user("Loc B");
        User someone = data.user("Loc Viewer");
        UUID gameId = createTeamGame(leaderA, team(leaderA, "Loc Team A"),
                addCard(leaderA, "8600123412341111"), slot(3, 18));

        // Waiting for an opponent: the creating captain, and anyone else who opens it.
        expectLocation(detail(gameId, leaderA).andExpect(jsonPath("$.data.status").value("open")),
                "$.data.location", stadium);
        expectLocation(detail(gameId, someone), "$.data.location", stadium);

        // The other captain on the board, before accepting.
        ResultActions board = mvc.perform(get("/api/games/challenges").param("size", "100")
                        .header("Authorization", data.bearer(leaderB)))
                .andExpect(status().isOk());
        String body = board.andReturn().getResponse().getContentAsString();
        java.util.List<Integer> index = com.jayway.jsonpath.JsonPath.read(body,
                "$.data.content[?(@.gameId == '" + gameId + "')].durationHours");
        assertThat(index).hasSize(1);
        expectLocation(board, "$.data.content[0].location", stadium);

        // After accepting: both captains.
        join(leaderB, gameId, team(leaderB, "Loc Team B"), addCard(leaderB, "8600123412342222"))
                .andExpect(status().isOk());
        expectLocation(detail(gameId, leaderB), "$.data.location", stadium);
        expectLocation(detail(gameId, leaderA), "$.data.location", stadium);
    }

    @Test
    void aStadiumWithoutCoordinatesOrPhotosStillHasItsAddress() throws Exception {
        Stadium bare = data.stadium(owner, "Bare Arena");
        User leader = data.user("Bare A");
        var game = data.teamGame(bare, leader, slot(4, 18));
        detail(game.getId(), leader)
                .andExpect(jsonPath("$.data.location.address").value("Toshkent, Bare Arena"))
                .andExpect(jsonPath("$.data.location.latitude").doesNotExist())
                .andExpect(jsonPath("$.data.location.landmark").doesNotExist())
                .andExpect(jsonPath("$.data.location.coverPhotoUrl").doesNotExist());
    }

    @Test
    void theBoardLoadsItsStadiumsWithThePageNotOnePerCard() throws Exception {
        User viewer = data.user("Board Viewer");
        for (int i = 0; i < 3; i++) {
            Stadium s = data.photos(data.stadium(owner, "Board Arena " + i), 1);
            locate(s, "Yunusobod", "Bozor oldida");
            User leader = data.user("Board Leader " + i);
            var game = data.teamGame(s, leader, slot(3 + i, 18));
            data.gameTeam(game, data.team(leader, "Board Team " + i),
                    uz.minipolya.minipolya_backend.entity.GameParticipant.Side.A);
        }

        queries.start();
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        mvc.perform(get("/api/games/challenges").param("size", "100")
                        .header("Authorization", data.bearer(viewer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].location.district").value("Yunusobod"))
                .andExpect(jsonPath("$.data.content[2].location.landmark").value("Bozor oldida"));

        // No stadium is fetched on its own: they come joined to the page.
        assertThat(statistics.getEntityStatistics(Stadium.class.getName()).getFetchCount()).isZero();
    }
}
