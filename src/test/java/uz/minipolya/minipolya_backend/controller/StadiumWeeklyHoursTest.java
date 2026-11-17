package uz.minipolya.minipolya_backend.controller;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uz.minipolya.minipolya_backend.entity.Stadium;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.StadiumRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.TestData;
import uz.minipolya.minipolya_backend.util.WeeklyHours;

import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The weekly opening schedule through the API: what the owner sends on create and update,
 * what every stadium response carries, and the admin's own edit.
 */
@DatabaseTest
class StadiumWeeklyHoursTest {

    /** Friday (4) 08:00–02:00, Sunday (6) closed, the rest 08:00–00:00. */
    private static final String MIXED_WEEK = """
            {"days":[
              {"dayOfWeek":0,"closed":false,"openTime":"08:00","closeTime":"00:00"},
              {"dayOfWeek":1,"closed":false,"openTime":"08:00","closeTime":"00:00"},
              {"dayOfWeek":2,"closed":false,"openTime":"08:00","closeTime":"00:00"},
              {"dayOfWeek":3,"closed":false,"openTime":"08:00","closeTime":"00:00"},
              {"dayOfWeek":4,"closed":false,"openTime":"08:00","closeTime":"02:00"},
              {"dayOfWeek":5,"closed":false,"openTime":"10:00","closeTime":"00:00"},
              {"dayOfWeek":6,"closed":true}
            ]}""";

    private static final String BASE = """
            {"name":"Hours Arena","address":"Chilonzor 9, 12","district":"Chilonzor",
             "phone":"+998901234567","latitude":41.2856,"longitude":69.2034,
             "format":"5x5","surfaceType":"artificial","pricePerHour":15000000""";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private StadiumRepository stadiumRepository;

    private User owner;
    private String ownerToken;
    private String adminToken;

    @BeforeEach
    void setUp() {
        owner = data.owner("Hours Owner");
        ownerToken = data.bearer(owner);
        adminToken = data.bearer(data.admin("Hours Admin"));
    }

    private ResultActions create(String json) throws Exception {
        return mvc.perform(post("/api/owner/stadiums").header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions update(UUID id, String json) throws Exception {
        return mvc.perform(put("/api/owner/stadiums/" + id).header("Authorization", ownerToken)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions adminHours(UUID id, String json, String token) throws Exception {
        return mvc.perform(put("/api/admin/stadiums/" + id + "/hours").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private UUID createWith(String tail) throws Exception {
        String body = create(BASE + tail + "}").andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.data.id"));
    }

    private WeeklyHours storedHours(UUID id) {
        Stadium stadium = stadiumRepository.findById(id).orElseThrow();
        return stadium.weeklyHours();
    }

    // ---- the owner's create ----

    @Test
    void createWithoutHoursKeepsTheSameWindowEveryDay() throws Exception {
        UUID id = createWith(",\"openTime\":\"08:00\",\"closeTime\":\"23:00\"");

        WeeklyHours hours = storedHours(id);
        assertThat(hours.days()).hasSize(7).allSatisfy(day -> {
            assertThat(day.closed()).isFalse();
            assertThat(day.open()).isEqualTo(LocalTime.of(8, 0));
            assertThat(day.close()).isEqualTo(LocalTime.of(23, 0));
        });
    }

    @Test
    void createWithAWeeklyScheduleStoresIt() throws Exception {
        UUID id = createWith(",\"hours\":" + MIXED_WEEK);

        WeeklyHours hours = storedHours(id);
        assertThat(hours.day(4).close()).isEqualTo(LocalTime.of(2, 0));
        assertThat(hours.day(4).lengthHours()).isEqualTo(18);
        assertThat(hours.day(5).open()).isEqualTo(LocalTime.of(10, 0));
        assertThat(hours.day(6).closed()).isTrue();
    }

    @Test
    void theScheduleIsInTheResponseMondayFirst() throws Exception {
        UUID id = createWith(",\"hours\":" + MIXED_WEEK);

        mvc.perform(get("/api/owner/stadiums/" + id).header("Authorization", ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hours", org.hamcrest.Matchers.hasSize(7)))
                .andExpect(jsonPath("$.data.hours[0].dayOfWeek").value(0))
                .andExpect(jsonPath("$.data.hours[4].closeTime").value("02:00:00"))
                .andExpect(jsonPath("$.data.hours[6].closed").value(true))
                .andExpect(jsonPath("$.data.hours[6].openTime").doesNotExist());
    }

    @Test
    void theLegacyOpenAndCloseAnswerTheFirstOpenDay() throws Exception {
        UUID id = createWith(",\"hours\":" + MIXED_WEEK);

        mvc.perform(get("/api/owner/stadiums/" + id).header("Authorization", ownerToken))
                .andExpect(jsonPath("$.data.openTime").value("08:00:00"))
                .andExpect(jsonPath("$.data.closeTime").value("00:00:00"));
    }

    @Test
    void createWithoutHoursOrTimesIsRefused() throws Exception {
        create(BASE + "}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("openTime is required"));
    }

    @Test
    void anInvalidScheduleIsRefusedWithTheRuleMessage() throws Exception {
        String tooLate = MIXED_WEEK.replace("\"dayOfWeek\":4,\"closed\":false,\"openTime\":\"08:00\",\"closeTime\":\"02:00\"",
                "\"dayOfWeek\":4,\"closed\":false,\"openTime\":\"08:00\",\"closeTime\":\"07:00\"");
        create(BASE + ",\"hours\":" + tooLate + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("A closing time at or before the opening time means the next morning,"
                                + " so it must be 06:00 or earlier"));
    }

    @Test
    void aScheduleWithNoGapBeforeTheNextOpeningIsRefused() throws Exception {
        String noGap = MIXED_WEEK.replace("\"dayOfWeek\":5,\"closed\":false,\"openTime\":\"10:00\"",
                "\"dayOfWeek\":5,\"closed\":false,\"openTime\":\"02:00\"");
        create(BASE + ",\"hours\":" + noGap + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message")
                        .value("There must be at least 1 hour between Friday's closing and Saturday's opening"));
    }

    @Test
    void sendingBothHoursAndTimesIsRefused() throws Exception {
        create(BASE + ",\"openTime\":\"08:00\",\"closeTime\":\"23:00\",\"hours\":" + MIXED_WEEK + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Send either hours or openTime and closeTime, not both"));
    }

    // ---- the owner's update ----

    @Test
    void updateReplacesTheWholeSchedule() throws Exception {
        UUID id = createWith(",\"openTime\":\"08:00\",\"closeTime\":\"23:00\"");

        update(id, "{\"hours\":" + MIXED_WEEK + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hours[4].closeTime").value("02:00:00"));

        assertThat(storedHours(id).day(6).closed()).isTrue();
    }

    @Test
    void updateWithTheLegacyTimesStillRewritesEveryDay() throws Exception {
        UUID id = createWith(",\"hours\":" + MIXED_WEEK);

        update(id, "{\"openTime\":\"09:00\",\"closeTime\":\"22:00\"}").andExpect(status().isOk());

        assertThat(storedHours(id).days()).allSatisfy(day -> {
            assertThat(day.closed()).isFalse();
            assertThat(day.open()).isEqualTo(LocalTime.of(9, 0));
        });
    }

    @Test
    void anUpdateThatSendsNoHoursLeavesTheScheduleAlone() throws Exception {
        UUID id = createWith(",\"hours\":" + MIXED_WEEK);

        update(id, "{\"name\":\"Hours Arena Renamed\"}").andExpect(status().isOk());

        assertThat(storedHours(id).day(4).close()).isEqualTo(LocalTime.of(2, 0));
        assertThat(storedHours(id).day(6).closed()).isTrue();
    }

    // ---- the admin's edit ----

    @Test
    void anAdminEditsTheSchedule() throws Exception {
        UUID id = createWith(",\"openTime\":\"08:00\",\"closeTime\":\"23:00\"");

        adminHours(id, "{\"hours\":" + MIXED_WEEK + "}", adminToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hours[4].closeTime").value("02:00:00"))
                .andExpect(jsonPath("$.message").value("Opening hours updated"));

        assertThat(storedHours(id).day(4).lengthHours()).isEqualTo(18);
    }

    @Test
    void theAdminsEditIsRefusedWhenItBreaksARule() throws Exception {
        UUID id = createWith(",\"openTime\":\"08:00\",\"closeTime\":\"23:00\"");

        adminHours(id, "{\"hours\":{\"days\":[]}}", adminToken)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("hours must list all 7 days of the week exactly once"));
    }

    @Test
    void aPlayerCannotEditTheSchedule() throws Exception {
        UUID id = createWith(",\"openTime\":\"08:00\",\"closeTime\":\"23:00\"");

        adminHours(id, "{\"hours\":" + MIXED_WEEK + "}", data.bearer(data.user("Hours Player")))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownStadiumIsNotFound() throws Exception {
        adminHours(UUID.randomUUID(), "{\"hours\":" + MIXED_WEEK + "}", adminToken)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Stadium not found"));
    }
}
