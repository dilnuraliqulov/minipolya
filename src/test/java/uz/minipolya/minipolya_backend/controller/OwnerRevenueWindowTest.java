package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.Booking;
import uz.minipolya.minipolya_backend.entity.Payment;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R43: the owner's daily revenue — 7 or 30 days, every day present, oldest first, ending today. */
@DatabaseTest
class OwnerRevenueWindowTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;

    private User owner;
    private String ownerToken;
    private Stadium stadium;
    private String url;

    @BeforeEach
    void setUp() {
        owner = data.owner("Revenue Owner");
        ownerToken = data.bearer(owner);
        stadium = data.stadium(owner, "Revenue Arena");
        url = "/api/owner/dashboard/stadiums/" + stadium.getId() + "/revenue";
        Booking booking = data.booking(stadium, data.user("Revenue Player"),
                OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).truncatedTo(ChronoUnit.HOURS), "confirmed");
        Payment payment = data.cardPayment(booking, 7_000_000L, 7_000_000L, 0L, null, Payment.Status.COMPLETED);
        data.save(payment); // created now: today's revenue
    }

    @Test
    void thirtyDaysByDefaultEveryDayPresent() throws Exception {
        LocalDate today = LocalDate.now(TASHKENT);
        mvc.perform(get(url).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stadiumId").value(stadium.getId().toString()))
                .andExpect(jsonPath("$.data.dailyRevenue", hasSize(30)))
                .andExpect(jsonPath("$.data.dailyRevenue[0].date").value(today.minusDays(29).toString()))
                .andExpect(jsonPath("$.data.dailyRevenue[0].revenue").value(0))
                .andExpect(jsonPath("$.data.dailyRevenue[0].bookingCount").value(0))
                .andExpect(jsonPath("$.data.dailyRevenue[29].date").value(today.toString()))
                .andExpect(jsonPath("$.data.dailyRevenue[29].revenue").value(7_000_000))
                .andExpect(jsonPath("$.data.dailyRevenue[29].bookingCount").value(1));
    }

    @Test
    void sevenDays() throws Exception {
        LocalDate today = LocalDate.now(TASHKENT);
        mvc.perform(get(url).param("days", "7").header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.dailyRevenue", hasSize(7)))
                .andExpect(jsonPath("$.data.dailyRevenue[0].date").value(today.minusDays(6).toString()))
                .andExpect(jsonPath("$.data.dailyRevenue[6].revenue").value(7_000_000));
    }

    @Test
    void anyOtherWindowIs400() throws Exception {
        for (String days : new String[]{"14", "0", "31", "-7"}) {
            mvc.perform(get(url).param("days", days).header("Authorization", ownerToken))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("days must be 7 or 30"));
        }
        mvc.perform(get(url).param("days", "week").header("Authorization", ownerToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    void accessRules() throws Exception {
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(get(url).header("Authorization", data.bearer(data.user("Player"))))
                .andExpect(status().isForbidden());
        mvc.perform(get(url).header("Authorization", data.bearer(data.owner("Other Owner"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not own this stadium"));
    }
}
