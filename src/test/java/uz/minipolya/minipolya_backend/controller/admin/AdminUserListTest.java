package uz.minipolya.minipolya_backend.controller.admin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import uz.minipolya.minipolya_backend.entity.User;
import uz.minipolya.minipolya_backend.repository.UserRepository;
import uz.minipolya.minipolya_backend.support.DatabaseTest;
import uz.minipolya.minipolya_backend.support.QueryCounter;
import uz.minipolya.minipolya_backend.support.TestData;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** R13: the user list filtered by role and status, on the server. */
@DatabaseTest
class AdminUserListTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private TestData data;
    @Autowired
    private QueryCounter queries;
    @Autowired
    private UserRepository userRepository;

    private String adminToken;

    @BeforeEach
    void setUp() {
        adminToken = data.bearer(data.admin("Zz R13 Admin"));
    }

    @Test
    void accessIsForAdminsOnly() throws Exception {
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users").header("Authorization", data.bearer(data.user("Plain"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void roleAndStatusFiltersCombineWithSearch() throws Exception {
        data.user("Zz R13 Player");
        User owner = data.owner("Zz R13 Owner");
        data.stadium(owner, "R13 Arena");
        data.stadium(owner, "R13 Arena 2");
        User blocked = data.user("Zz R13 Blocked");
        blocked.setBlocked(true);
        userRepository.save(blocked);

        mvc.perform(get("/api/admin/users").header("Authorization", adminToken)
                        .param("search", "zz r13").param("role", "player"))
                .andExpect(jsonPath("$.data.content", hasSize(2))); // the player and the blocked player
        mvc.perform(get("/api/admin/users").header("Authorization", adminToken)
                        .param("search", "zz r13").param("role", "owner"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].isOwner").value(true))
                .andExpect(jsonPath("$.data.content[0].stadiumCount").value(2));
        mvc.perform(get("/api/admin/users").header("Authorization", adminToken)
                        .param("search", "zz r13").param("role", "admin"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].isAdmin").value(true));
        mvc.perform(get("/api/admin/users").header("Authorization", adminToken)
                        .param("search", "zz r13").param("status", "blocked"))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].name").value("Zz R13 Blocked"))
                .andExpect(jsonPath("$.data.content[0].isBlocked").value(true));
        mvc.perform(get("/api/admin/users").header("Authorization", adminToken)
                        .param("search", "zz r13").param("status", "active").param("role", "player"))
                .andExpect(jsonPath("$.data.content", hasSize(1)));
    }

    @Test
    void phonesMatchIgnoringSpacesAndPlus() throws Exception {
        User user = data.user("Phone Person");
        String phone = user.getPhone(); // 99890xxxxxxx
        String typed = "+" + phone.substring(0, 3) + " " + phone.substring(3, 5) + " " + phone.substring(5);

        mvc.perform(get("/api/admin/users").header("Authorization", adminToken).param("search", typed))
                .andExpect(jsonPath("$.data.content", hasSize(1)))
                .andExpect(jsonPath("$.data.content[0].name").value("Phone Person"));
    }

    @Test
    void unknownFilterValuesAre400() throws Exception {
        mvc.perform(get("/api/admin/users").header("Authorization", adminToken).param("role", "superuser"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid role"));
        mvc.perform(get("/api/admin/users").header("Authorization", adminToken).param("status", "gone"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid status"));
    }

    @Test
    void aPageOfUsersCostsAFixedNumberOfQueries() throws Exception {
        for (int i = 0; i < 22; i++) {
            User user = i % 3 == 0 ? data.owner("Bulk R13 " + i) : data.user("Bulk R13 " + i);
            if (user.isOwner()) {
                data.stadium(user, "Bulk Stadium " + i);
            }
            data.booking(data.stadium(data.owner("Host " + i), "Host Arena " + i), user,
                    OffsetDateTime.parse("2030-05-01T05:00:00Z").plusHours(i), "confirmed");
        }
        queries.start();

        mvc.perform(get("/api/admin/users").header("Authorization", adminToken)
                        .param("search", "Bulk R13").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content", hasSize(22)))
                .andExpect(jsonPath("$.data.content[0].bookingCount").value(1));

        // auth user, users, count, booking counts, stadium counts
        assertThat(queries.count()).isLessThanOrEqualTo(5);
    }
}
