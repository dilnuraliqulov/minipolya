package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.audit.AuditAction;
import uz.minipolya.minipolya_backend.audit.AuditEntityType;
import uz.minipolya.minipolya_backend.audit.AuditEntry;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.Team;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.service.AuditService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.QueryCounter;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R15: every booking and every game for the admin panel, against a real database. */
@DatabaseTest
class AdminActivityControllerTest {

    /** 10:00 Tashkent on 2030-03-04 — far enough ahead that nothing else books it. */
    private static final OffsetDateTime BASE = OffsetDateTime.parse("2030-03-04T05:00:00Z");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private QueryCounter queries;
    @Autowired
    private AuditService auditService;

    private User admin;
    private String adminToken;
    private User owner;
    private Stadium stadium;

    @BeforeEach
    void setUp() {
        admin = data.admin("Admin Adminov");
        adminToken = data.bearer(admin);
        owner = data.owner("Owner Egasi");
        stadium = data.stadium(owner, "Arena R15");
    }

    // ---- Access ----

    @Test
    void withoutATokenItIs401() throws Exception {
        mvc.perform(get("/api/admin/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/games")).andExpect(status().isUnauthorized());
    }

    @Test
    void aNonAdminGets403() throws Exception {
        String player = data.bearer(data.user("Player"));
        mvc.perform(get("/api/admin/bookings").header("Authorization", player)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/games").header("Authorization", player)).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/bookings/" + java.util.UUID.randomUUID()).header("Authorization", player))
                .andExpect(status().isForbidden());
    }

    // ---- Bookings ----

    @Test
    void aPageOfBookingsCostsAFixedNumberOfQueries() throws Exception {
        User player = data.user("Many Bookings");
        for (int i = 0; i < 25; i++) {
            Booking booking = data.booking(stadium, player, BASE.plusHours(i), "confirmed");
            data.cardPayment(booking, 20_310_000L, 20_304_569L, 310_000L, 304_650L, Payment.Status.PAID);
        }
        queries.start();

        mvc.perform(get("/api/admin/bookings").header("Authorization", adminToken)
                        .param("stadiumId", stadium.getId().toString()).param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(25)))
                .andExpect(jsonPath("$.data.totalElements").value(25));

        // auth user, bookings (+stadium +user), count, payments, settings
        assertThat(queries.count()).isLessThanOrEqualTo(6);
    }

    @Test
    void aBookingCarriesItsPricesAndHowItWasPaid() throws Exception {
        User player = data.user("Dilshod Karimov");
        Booking booking = data.booking(stadium, player, BASE, "confirmed");
        data.cardPayment(booking, 10_160_000L, 10_152_285L, 160_000L, 152_400L, Payment.Status.PAID);
        Payment refunded = data.cardPayment(booking, 5_080_000L, 5_076_143L, 80_000L, 76_200L, Payment.Status.REFUNDED);
        data.cashPayment(booking, 5_000_000L);
        assertThat(refunded.getId()).isNotNull();

        mvc.perform(get("/api/admin/bookings/" + booking.getId()).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(booking.getId().toString()))
                .andExpect(jsonPath("$.data.stadiumName").value("Arena R15"))
                .andExpect(jsonPath("$.data.userName").value("Dilshod Karimov"))
                .andExpect(jsonPath("$.data.userPhone").value(player.getPhone()))
                .andExpect(jsonPath("$.data.netPrice").value(20_000_000))
                // playerAmount(20 000 000) at 150 bps, rounded up to 10 000 tiyin
                .andExpect(jsonPath("$.data.price").value(20_310_000))
                .andExpect(jsonPath("$.data.cardFee").value(310_000))
                // the refunded payment no longer counts
                .andExpect(jsonPath("$.data.paidInApp").value(10_160_000))
                .andExpect(jsonPath("$.data.cashReceived").value(5_000_000))
                .andExpect(jsonPath("$.data.cashReceivedAt").exists())
                .andExpect(jsonPath("$.data.booking").doesNotExist());
    }

    @Test
    void aBookingWithNoCashOmitsTheCashFields() throws Exception {
        Booking booking = data.booking(stadium, data.user("No Cash"), BASE, "pending");

        mvc.perform(get("/api/admin/bookings/" + booking.getId()).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paidInApp").value(0))
                .andExpect(jsonPath("$.data.cashReceived").doesNotExist())
                .andExpect(jsonPath("$.data.cashReceivedAt").doesNotExist());
    }

    @Test
    void searchMatchesNamesAndPhonesIgnoringCaseSpacesAndPlus() throws Exception {
        User player = data.user("Jasur Toshmatov");
        data.booking(stadium, player, BASE, "confirmed");
        data.walkIn(stadium, "Walk In Mijoz", "+998 90 555 44 33", BASE.plusHours(1));
        data.booking(stadium, data.user("Someone Else"), BASE.plusHours(2), "confirmed");

        mvc.perform(get("/api/admin/bookings").header("Authorization", adminToken).param("search", "jasur"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].userName").value("Jasur Toshmatov"));

        String phone = player.getPhone(); // stored as 99890xxxxxxx
        String typed = "+" + phone.substring(0, 3) + " " + phone.substring(3, 5) + " " + phone.substring(5);
        mvc.perform(get("/api/admin/bookings").header("Authorization", adminToken).param("search", typed))
                .andExpect(jsonPath("$.data.content", hasSize(1)));

        mvc.perform(get("/api/admin/bookings").header("Authorization", adminToken).param("search", "MIJOZ"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].clientName").value("Walk In Mijoz"))
                .andExpect(jsonPath("$.data.content[0].userName").doesNotExist());

        mvc.perform(get("/api/admin/bookings").header("Authorization", adminToken).param("search", "90555 4433"))
                .andExpect(jsonPath("$.data.content", hasSize(1)));
    }

    @Test
    void filtersCombineAndTheNewestStartComesFirst() throws Exception {
        User player = data.user("Filter Player");
        Stadium other = data.stadium(owner, "Other Arena");
        data.booking(stadium, player, BASE, "confirmed");
        data.booking(stadium, player, BASE.plusDays(1), "cancelled_by_user");
        data.booking(stadium, data.user("Not Him"), BASE.plusDays(1).plusHours(2), "confirmed");
        data.booking(other, player, BASE.plusDays(2), "confirmed");

        mvc.perform(get("/api/admin/bookings").header("Authorization", adminToken)
                        .param("userId", player.getId().toString()))
                .andExpect(jsonPath("$.data.content", hasSize(3)))
                .andExpect(jsonPath("$.data.content[0].stadiumName").value("Other Arena"));

        mvc.perform(get("/api/admin/bookings").header("Authorization", adminToken)
                        .param("userId", player.getId().toString()).param("status", "confirmed")
                        .param("stadiumId", stadium.getId().toString()))
                .andExpect(jsonPath("$.data.content", hasSize(1)));

        // 2030-03-05 in Tashkent: only the bookings starting that day
        mvc.perform(get("/api/admin/bookings").header("Authorization", adminToken)
                        .param("stadiumId", stadium.getId().toString())
                        .param("from", "2030-03-05").param("to", "2030-03-05"))
                .andExpect(jsonPath("$.data.content", hasSize(2)));
    }

    @Test
    void badInputIs400AndAnUnknownBookingIs404() throws Exception {
        mvc.perform(get("/api/admin/bookings").header("Authorization", adminToken)
                        .param("from", "2030-03-05").param("to", "2030-03-04"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to must be on or after from"));
        mvc.perform(get("/api/admin/bookings/not-a-uuid").header("Authorization", adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid id"));
        mvc.perform(get("/api/admin/bookings/" + java.util.UUID.randomUUID()).header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Booking not found"));
    }

    // ---- Games ----

    @Test
    void aPageOfGamesCostsAFixedNumberOfQueries() throws Exception {
        User creator = data.user("Game Creator");
        for (int i = 0; i < 22; i++) {
            Game game = i % 2 == 0
                    ? data.casualGame(stadium, creator, BASE.plusHours(i))
                    : data.teamGame(stadium, creator, BASE.plusHours(i));
            if (i % 2 == 0) {
                data.participant(game, creator, null);
            } else {
                data.gameTeam(game, data.team(creator, "Team " + i), GameParticipant.Side.A);
            }
        }
        queries.start();

        mvc.perform(get("/api/admin/games").header("Authorization", adminToken)
                        .param("stadiumId", stadium.getId().toString()).param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(22)));

        // auth user, games (+stadium +creator), count, teams, participant counts, audit
        assertThat(queries.count()).isLessThanOrEqualTo(7);
    }

    @Test
    void aTeamGameShowsItsTeamsAndACancelledOneWhyItEnded() throws Exception {
        User leaderA = data.user("Leader A");
        User leaderB = data.user("Leader B");
        Team lions = data.team(leaderA, "Lions");
        Team tigers = data.team(leaderB, "Tigers");
        Game confirmed = data.teamGame(stadium, leaderA, BASE);
        data.gameTeam(confirmed, lions, GameParticipant.Side.A);
        data.gameTeam(confirmed, tigers, GameParticipant.Side.B);
        data.participant(confirmed, leaderA, GameParticipant.Side.A);
        data.participant(confirmed, leaderB, GameParticipant.Side.B);

        Game waiting = data.teamGame(stadium, leaderA, BASE.plusHours(3));
        data.gameTeam(waiting, lions, GameParticipant.Side.A);
        waiting.setStatus("cancelled_not_filled");
        auditService.record(AuditEntry.system(AuditAction.GAME_AUTO_CANCELLED)
                .entity(AuditEntityType.GAME, waiting.getId(), "Arena R15")
                .reason("No opponent was found in time"));

        mvc.perform(get("/api/admin/games").header("Authorization", adminToken)
                        .param("stadiumId", stadium.getId().toString()))
                .andExpect(jsonPath("$.data.content", hasSize(2)))
                .andExpect(jsonPath("$.data.content[0].id").value(waiting.getId().toString()))
                .andExpect(jsonPath("$.data.content[0].teamAName").value("Lions"))
                .andExpect(jsonPath("$.data.content[0].teamBName").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].cancellationReason").value("No opponent was found in time"))
                .andExpect(jsonPath("$.data.content[0].cancelledAt").exists())
                .andExpect(jsonPath("$.data.content[0].maxPlayers").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].teamBName").value("Tigers"));

        mvc.perform(get("/api/admin/games/" + confirmed.getId()).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("team_vs_team"))
                .andExpect(jsonPath("$.data.teams", hasSize(2)))
                .andExpect(jsonPath("$.data.teams[?(@.side=='a')].teamName").value("Lions"))
                .andExpect(jsonPath("$.data.teams[?(@.side=='a')].advanceAmount").value(5_000_000))
                .andExpect(jsonPath("$.data.teams[?(@.side=='b')].players[0].userName").value("Leader B"))
                .andExpect(jsonPath("$.data.participants").doesNotExist())
                .andExpect(jsonPath("$.data.cancelledAt").doesNotExist());

        // search by either team's name, or the creator's
        mvc.perform(get("/api/admin/games").header("Authorization", adminToken).param("search", "tiger"))
                .andExpect(jsonPath("$.data.content", hasSize(1)));
        // games the user played in
        mvc.perform(get("/api/admin/games").header("Authorization", adminToken)
                        .param("userId", leaderB.getId().toString()))
                .andExpect(jsonPath("$.data.content", hasSize(1)));
    }

    @Test
    void aCasualGameListsItsParticipants() throws Exception {
        User creator = data.user("Casual Creator");
        Game game = data.casualGame(stadium, creator, BASE);
        data.participant(game, creator, null);
        data.participant(game, data.user("Friend One"), null);

        mvc.perform(get("/api/admin/games/" + game.getId()).header("Authorization", adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("casual"))
                .andExpect(jsonPath("$.data.currentPlayers").value(2))
                .andExpect(jsonPath("$.data.maxPlayers").value(10))
                .andExpect(jsonPath("$.data.serviceFeePerPerson").value(0))
                .andExpect(jsonPath("$.data.participants", hasSize(2)))
                .andExpect(jsonPath("$.data.participants[1].userName").value("Friend One"))
                .andExpect(jsonPath("$.data.participants[1].status").value("joined"))
                .andExpect(jsonPath("$.data.teams").doesNotExist());

        mvc.perform(get("/api/admin/games/" + java.util.UUID.randomUUID()).header("Authorization", adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Game not found"));
    }
}
