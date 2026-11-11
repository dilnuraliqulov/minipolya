package uz.minipolya.minipolya_backend.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.StadiumClosure;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.StadiumClosureRepository;
import uz.minipolya.minipolya_backend.service.AvailabilityService;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.QueryCounter;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code GET /api/stadiums?date=}: how many hours of that day can still be booked, per stadium. */
@DatabaseTest
class StadiumFreeSlotsTest {

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    /** {@code TestData.stadium} is open 08:00–23:00. */
    private static final int ALL_HOURS = 15;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private StadiumClosureRepository closures;
    @Autowired
    private AvailabilityService availability;
    @Autowired
    private QueryCounter queries;

    private final LocalDate date = LocalDate.now(TASHKENT).plusDays(3);
    private User owner;
    private User player;

    @BeforeEach
    void setUp() {
        owner = data.owner("Slots Owner");
        player = data.user("Slots Player");
    }

    private OffsetDateTime at(int hour) {
        return date.atTime(hour, 0).atZone(TASHKENT).toOffsetDateTime();
    }

    private void closure(Stadium stadium, LocalTime from, LocalTime to) {
        closures.saveAndFlush(StadiumClosure.builder().stadium(stadium)
                .startDate(date).endDate(date).startTime(from).endTime(to).reason("Tamir").build());
    }

    private ResultActions list(String q, String day) throws Exception {
        var request = get("/api/stadiums").param("q", q);
        if (day != null) {
            request.param("date", day);
        }
        return mvc.perform(request);
    }

    @Test
    void anUntouchedDayHasEveryOpeningHourFree() throws Exception {
        data.stadium(owner, "Slots Empty");

        list("Slots Empty", date.toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].freeSlotsCount").value(ALL_HOURS));
    }

    @Test
    void pendingAndConfirmedBookingsAndClosuresAreTakenOff() throws Exception {
        Stadium stadium = data.stadium(owner, "Slots Busy");
        data.booking(stadium, player, at(10), "confirmed");
        data.booking(stadium, player, at(11), "pending");
        data.booking(stadium, player, at(12), "cancelled_by_user");
        data.walkIn(stadium, "Walk-in", "+998901112233", at(13));
        closure(stadium, LocalTime.of(20, 0), LocalTime.of(22, 0));

        list("Slots Busy", date.toString())
                .andExpect(jsonPath("$.data.content[0].freeSlotsCount").value(ALL_HOURS - 3 - 2));
    }

    @Test
    void aFullDayClosureLeavesNothing() throws Exception {
        Stadium stadium = data.stadium(owner, "Slots Closed");
        closure(stadium, null, null);

        list("Slots Closed", date.toString())
                .andExpect(jsonPath("$.data.content[0].freeSlotsCount").value(0));
    }

    @Test
    void aBookingOnAnotherDayDoesNotCount() throws Exception {
        Stadium stadium = data.stadium(owner, "Slots Other Day");
        data.booking(stadium, player, at(10).plusDays(1), "confirmed");

        list("Slots Other Day", date.toString())
                .andExpect(jsonPath("$.data.content[0].freeSlotsCount").value(ALL_HOURS));
    }

    @Test
    void theDefaultIsTodayAndItAgreesWithAvailability() throws Exception {
        Stadium stadium = data.stadium(owner, "Slots Today");

        long free = availability.getAvailability(stadium.getId(), null).getSlots().stream()
                .filter(slot -> "free".equals(slot.getStatus())).count();

        list("Slots Today", null)
                .andExpect(jsonPath("$.data.content[0].freeSlotsCount").value((int) free));
    }

    @Test
    void aDayOutsideTheBookingWindowHasNothingToBook() throws Exception {
        data.stadium(owner, "Slots Far");

        list("Slots Far", LocalDate.now(TASHKENT).minusDays(1).toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].freeSlotsCount").value(0));
        list("Slots Far", LocalDate.now(TASHKENT).plusWeeks(30).toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].freeSlotsCount").value(0));
    }

    @Test
    void aMalformedDateIs400() throws Exception {
        list("Slots", "tomorrow").andExpect(status().isBadRequest());
    }

    @Test
    void twoQueriesCoverAWholePageOfStadiums() {
        List<Stadium> stadiums = new ArrayList<>();
        for (int i = 0; i < 22; i++) {
            Stadium stadium = data.stadium(owner, "Slots Page " + i);
            data.booking(stadium, player, at(9 + (i % 10)), "confirmed");
            closure(stadium, LocalTime.of(22, 0), LocalTime.of(23, 0));
            stadiums.add(stadium);
        }

        queries.start();
        Map<UUID, Integer> counts = availability.freeSlotCounts(stadiums, date);

        assertThat(queries.count()).isEqualTo(2);
        assertThat(counts).hasSize(22).allSatisfy((id, count) -> assertThat(count).isEqualTo(ALL_HOURS - 2));
    }

    @Test
    void theListCostsTheSameForThreeStadiumsAsForTwenty() throws Exception {
        for (int i = 0; i < 22; i++) {
            Stadium stadium = data.stadium(owner, "Slots Cost " + i);
            data.booking(stadium, player, at(9 + (i % 10)), "confirmed");
        }

        queries.start();
        mvc.perform(get("/api/stadiums").param("q", "Slots Cost").param("date", date.toString()).param("size", "3"))
                .andExpect(jsonPath("$.data.content", hasSize(3)));
        long three = queries.count();

        queries.start();
        mvc.perform(get("/api/stadiums").param("q", "Slots Cost").param("date", date.toString()).param("size", "20"))
                .andExpect(jsonPath("$.data.content", hasSize(20)))
                .andExpect(jsonPath("$.data.content[19].freeSlotsCount").isNumber());

        assertThat(queries.count()).isEqualTo(three);
    }
}
