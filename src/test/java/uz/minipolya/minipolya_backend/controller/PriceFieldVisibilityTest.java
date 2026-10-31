package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Game;
import uz.minipolya.minipolya_backend.entity.GameParticipant;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.PricingRule;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.PricingRuleRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Which price fields players, owners and admins get, one test per endpoint family. Players
 * see one price — every amount is what they pay — and never a fee, a commission, an owner
 * price or any other breakdown: a player response carrying {@code cardFee} or
 * {@code netPrice} (or any such field) fails here.
 *
 * <p>Stadium price 200 000 so'm; with the default settings (150 bps, 100 so'm step) players
 * pay 203 100 so'm = 20 310 000 tiyin.
 */
@DatabaseTest
class PriceFieldVisibilityTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final long OWNER_PRICE = 20_000_000L;
    private static final long PLAYER_PRICE = 20_310_000L;

    /** Fields a player response must never carry, at any depth. */
    private static final List<String> BREAKDOWN = List.of(
            "cardFee", "netPrice", "ownerPrice", "playerPrice", "ownerReceives", "atmosFee",
            "platformFee", "ownerAdvance", "ownerRemainder", "feeAmount", "advanceAmount",
            "remainderByCard", "remainderInCash", "serviceFeePerPerson", "ownerAmount",
            "platformAmount", "commissionPercent", "playerMinBookingAmount");
    private static final Pattern KEY = Pattern.compile("\"([A-Za-z]+)\"\\s*:");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private PricingRuleRepository pricingRuleRepository;

    private User owner;
    private User player;
    private Stadium stadium;
    private String ownerToken;
    private String playerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        owner = data.owner("Visibility Owner");
        player = data.user("Visibility Player");
        stadium = data.stadium(owner, "Visibility Arena");
        stadium.setMinBookingAmount(5_000_000L);
        ownerToken = data.bearer(owner);
        playerToken = data.bearer(player);
        adminToken = data.bearer(data.admin("Visibility Admin"));
    }

    /** Fails on any breakdown field, and on any key naming a commission, card fee or net amount. */
    private static void assertPlayerSafe(ResultActions result) throws Exception {
        String body = result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        Matcher keys = KEY.matcher(body);
        while (keys.find()) {
            String key = keys.group(1);
            assertThat(BREAKDOWN).as("player response field %s in %s", key, body).doesNotContain(key);
            assertThat(key.toLowerCase()).as("player response field %s", key)
                    .doesNotContain("commission").doesNotContain("cardfee").doesNotStartWith("net");
        }
    }

    private OffsetDateTime tomorrowAt(int hour) {
        return LocalDate.now(TASHKENT).plusDays(2).atTime(hour, 0).atZone(TASHKENT).toOffsetDateTime();
    }

    @Test
    void stadiums() throws Exception {
        assertPlayerSafe(mvc.perform(get("/api/stadiums").param("q", "Visibility Arena"))
                .andExpect(jsonPath("$.data.content[0].pricePerHour").value(PLAYER_PRICE)));
        assertPlayerSafe(mvc.perform(get("/api/stadiums/" + stadium.getId()))
                .andExpect(jsonPath("$.data.pricePerHour").value(PLAYER_PRICE))
                .andExpect(jsonPath("$.data.minBookingAmount").value(5_080_000)));

        mvc.perform(get("/api/owner/stadiums").header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data[0].pricePerHour").value(OWNER_PRICE))
                .andExpect(jsonPath("$.data[0].playerPrice").value(PLAYER_PRICE))
                .andExpect(jsonPath("$.data[0].cardFee").doesNotExist())
                .andExpect(jsonPath("$.data[0].ownerReceives").doesNotExist());

        mvc.perform(get("/api/admin/stadiums/" + stadium.getId()).header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.pricePerHour").value(OWNER_PRICE))
                .andExpect(jsonPath("$.data.playerPrice").value(PLAYER_PRICE))
                .andExpect(jsonPath("$.data.ownerReceives").value(20_005_350))
                .andExpect(jsonPath("$.data.atmosFee").value(304_650))
                .andExpect(jsonPath("$.data.cardFee").doesNotExist());
    }

    @Test
    void availabilitySlots() throws Exception {
        assertPlayerSafe(mvc.perform(get("/api/stadiums/" + stadium.getId() + "/availability")
                        .param("date", LocalDate.now(TASHKENT).plusDays(2).toString()))
                .andExpect(jsonPath("$.data.slots[0].pricePerHour").value(PLAYER_PRICE)));
    }

    @Test
    void pricingRules() throws Exception {
        pricingRuleRepository.save(PricingRule.builder().stadium(stadium).dayOfWeek(5)
                .startTime(LocalTime.of(18, 0)).endTime(LocalTime.of(23, 0)).pricePerHour(30_000_000L).build());

        assertPlayerSafe(mvc.perform(get("/api/stadiums/" + stadium.getId() + "/pricing"))
                .andExpect(jsonPath("$.data[0].pricePerHour").value(30_460_000)));

        mvc.perform(get("/api/owner/stadiums/" + stadium.getId() + "/pricing").header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data[0].pricePerHour").value(30_000_000))
                .andExpect(jsonPath("$.data[0].playerPrice").value(30_460_000))
                .andExpect(jsonPath("$.data[0].cardFee").doesNotExist());
    }

    @Test
    void bookings() throws Exception {
        Booking booking = data.booking(stadium, player, tomorrowAt(18), "confirmed");
        booking.setCardPrice(PLAYER_PRICE);
        data.save(Payment.builder().booking(booking).user(player).amount(5_080_000L).ownerAmount(5_080_000L)
                .platformAmount(0L).commissionPercent(0).method(Payment.Method.CARD)
                .paymentType(Payment.PaymentType.BOOKING).provider(Payment.Provider.ATMOS)
                .status(Payment.Status.PAID).build());
        booking.setPaidAmount(5_080_000L);
        booking.setPaymentStatus(Booking.PaymentStatus.PARTIALLY_PAID);

        assertPlayerSafe(mvc.perform(get("/api/bookings/" + booking.getId()).header("Authorization", playerToken))
                .andExpect(jsonPath("$.data.price").value(PLAYER_PRICE))
                .andExpect(jsonPath("$.data.paidAmount").value(5_080_000))
                .andExpect(jsonPath("$.data.remainingAmount").value(PLAYER_PRICE - 5_080_000))
                .andExpect(jsonPath("$.data.paidInApp").doesNotExist()));
        assertPlayerSafe(mvc.perform(get("/api/bookings").header("Authorization", playerToken)));

        mvc.perform(get("/api/owner/stadiums/" + stadium.getId() + "/bookings").header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data.content[0].price").value(PLAYER_PRICE))
                .andExpect(jsonPath("$.data.content[0].ownerPrice").value(OWNER_PRICE))
                .andExpect(jsonPath("$.data.content[0].paidInApp").value(5_080_000))
                .andExpect(jsonPath("$.data.content[0].remainingAmount").value(PLAYER_PRICE - 5_080_000))
                .andExpect(jsonPath("$.data.content[0].ownerReceives").value(5_003_800))
                .andExpect(jsonPath("$.data.content[0].atmosFee").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].cardFee").doesNotExist());

        mvc.perform(get("/api/admin/bookings/" + booking.getId()).header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.price").value(PLAYER_PRICE))
                .andExpect(jsonPath("$.data.ownerPrice").value(OWNER_PRICE))
                .andExpect(jsonPath("$.data.ownerReceives").value(5_003_800))
                .andExpect(jsonPath("$.data.atmosFee").value(76_200))
                .andExpect(jsonPath("$.data.cardFee").doesNotExist());
    }

    @Test
    void games() throws Exception {
        Game game = data.teamGame(stadium, player, tomorrowAt(20));
        data.gameTeam(game, data.team(player, "Visibility A"), GameParticipant.Side.A);
        data.participant(game, player, GameParticipant.Side.A);
        User other = data.user("Visibility Other");

        assertPlayerSafe(mvc.perform(get("/api/games/" + game.getId()).header("Authorization", playerToken))
                // advance 50 000 + fee 25 000 = 75 000 so'm → 76 200 as one price
                .andExpect(jsonPath("$.data.teams[0].price").value(7_620_000)));
        assertPlayerSafe(mvc.perform(get("/api/games/challenges").header("Authorization", data.bearer(other))));
        assertPlayerSafe(mvc.perform(get("/api/games/my").header("Authorization", playerToken)));

        Game casual = data.casualGame(stadium, player, tomorrowAt(10));
        assertPlayerSafe(mvc.perform(get("/api/games/" + casual.getId()).header("Authorization", playerToken))
                // 200 000 / 10 players = 20 000 so'm → 20 400 as one price
                .andExpect(jsonPath("$.data.price").value(2_040_000))
                .andExpect(jsonPath("$.data.totalPrice").value(20_400_000)));

        mvc.perform(get("/api/admin/games/" + game.getId()).header("Authorization", adminToken))
                .andExpect(jsonPath("$.data.teams[0].price").value(7_620_000))
                .andExpect(jsonPath("$.data.teams[0].platformFee").value(2_500_000))
                .andExpect(jsonPath("$.data.teams[0].ownerAdvance").value(5_000_000))
                .andExpect(jsonPath("$.data.teams[0].ownerRemainder").value(5_000_000));
    }

    @Test
    void payments() throws Exception {
        Booking booking = data.booking(stadium, player, tomorrowAt(18), "confirmed");
        data.cardPayment(booking, PLAYER_PRICE, PLAYER_PRICE, 304_650L, null, Payment.Status.PAID);

        assertPlayerSafe(mvc.perform(get("/api/payments/my").header("Authorization", playerToken))
                .andExpect(jsonPath("$.data.content[0].amount").value(PLAYER_PRICE)));
    }
}
