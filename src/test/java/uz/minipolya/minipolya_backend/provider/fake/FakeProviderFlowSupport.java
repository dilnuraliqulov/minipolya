package uz.minipolya.minipolya_backend.provider.fake;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.OwnerLegalDetails;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.SavedCard;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.BookingRepository;
import uz.minipolya.minipolya_backend.repository.GameRepository;
import uz.minipolya.minipolya_backend.repository.OwnerLegalDetailsRepository;
import uz.minipolya.minipolya_backend.repository.PaymentRepository;
import uz.minipolya.minipolya_backend.repository.SavedCardRepository;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.service.GameService;
import uz.minipolya.minipolya_backend.service.PaymentReconcileService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The business flows end to end, over HTTP, against the real FakePaymentProvider and its
 * database store — no mocks: cards are bound with fake numbers, charged, held, captured,
 * reversed and released, and each card's fake balance shows where the money went.
 */

abstract class FakeProviderFlowSupport {

    protected static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    protected static final long START = 1_000_000_000L;

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected TestData data;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StadiumRepository stadiums;
    @Autowired
    protected OwnerLegalDetailsRepository legalDetails;
    @Autowired
    protected SavedCardRepository savedCards;
    @Autowired
    protected BookingRepository bookings;
    @Autowired
    protected PaymentRepository payments;
    @Autowired
    protected GameRepository games;
    @Autowired
    protected GameService gameService;
    @Autowired
    protected PaymentReconcileService reconcileService;

    protected User owner;
    protected Stadium stadium;

    @BeforeEach
    void setUp() {
        owner = data.owner("Flow Owner");
        stadium = data.stadium(owner, "Flow Arena");
        stadium.setMinBookingAmount(5_000_000L);
        stadiums.save(stadium);
        legalDetails.save(OwnerLegalDetails.builder()
                .userId(owner.getId())
                .atmosContractStatus(OwnerLegalDetails.ContractStatus.SIGNED)
                .atmosRecipientId("rcp-flow-owner")
                .build());
    }

    // ---- helpers ----

    protected UUID addCard(User user, String number) throws Exception {
        String started = mvc.perform(post("/api/cards").header("Authorization", data.bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"cardNumber\":\"" + number + "\",\"expiry\":\"12/29\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bindingId = JsonPath.read(started, "$.data.bindingId");
        String confirmed = mvc.perform(post("/api/cards/confirm").header("Authorization", data.bearer(user))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bindingId\":\"" + bindingId + "\",\"smsCode\":\"482913\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(confirmed, "$.data.id"));
    }

    /** The fake card's balance, read without locking it. */
    protected long balance(UUID savedCardId) {
        SavedCard card = savedCards.findById(savedCardId).orElseThrow();
        return jdbc.queryForObject("SELECT balance FROM fake_payment_cards WHERE card_id = ?",
                Long.class, card.getProviderCardId());
    }

    protected OffsetDateTime slot(int daysAhead, int hour) {
        return LocalDate.now(TASHKENT).plusDays(daysAhead).atTime(hour, 0).atZone(TASHKENT).toOffsetDateTime();
    }

    protected ResultActions book(User player, UUID cardId, String option, OffsetDateTime startsAt) throws Exception {
        return mvc.perform(post("/api/bookings").header("Authorization", data.bearer(player))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"stadiumId\":\"" + stadium.getId() + "\",\"startsAt\":\"" + startsAt
                        + "\",\"cardId\":\"" + cardId + "\",\"paymentOption\":\"" + option + "\"}"));
    }

    protected UUID bookingId(ResultActions result) throws Exception {
        return UUID.fromString(JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.data.id"));
    }

    protected Payment onlyPayment(UUID bookingId, Payment.PaymentType type) {
        return payments.findByBookingId(bookingId).stream()
                .filter(p -> p.getPaymentType() == type).findFirst().orElseThrow();
    }

    protected UUID team(User leader, String name) throws Exception {
        String body = mvc.perform(post("/api/teams").header("Authorization", data.bearer(leader))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    protected UUID createTeamGame(User leader, UUID teamId, UUID cardId, OffsetDateTime startsAt) throws Exception {
        String body = mvc.perform(post("/api/games").header("Authorization", data.bearer(leader))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stadiumId\":\"" + stadium.getId() + "\",\"type\":\"team_vs_team\",\"maxPlayers\":10,"
                                + "\"startsAt\":\"" + startsAt + "\",\"teamAId\":\"" + teamId + "\",\"cardId\":\"" + cardId + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    protected ResultActions join(User leader, UUID gameId, UUID teamId, UUID cardId) throws Exception {
        return mvc.perform(post("/api/games/" + gameId + "/join").header("Authorization", data.bearer(leader))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"gameId\":\"" + gameId + "\",\"side\":\"b\",\"teamId\":\"" + teamId
                        + "\",\"cardId\":\"" + cardId + "\"}"));
    }

}
